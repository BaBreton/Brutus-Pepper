#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('start', 'setup', 'stop', 'status', 'check')]
    [string]$Action = 'start',
    [string]$LanIp = $env:PEPPER_LAN_IP,
    [ValidateRange(1, 3600)][int]$WaitSeconds = 180,
    [switch]$Open
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-LanIp([string]$Address) {
    if ($Address -notmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}$') { return $false }
    $parts = $Address.Split('.')
    foreach ($part in $parts) {
        if (($part.Length -gt 1 -and $part.StartsWith('0')) -or [int]$part -gt 255) { return $false }
    }
    return ([int]$parts[0] -gt 0 -and [int]$parts[0] -lt 224 -and
        $parts[0] -ne '127' -and -not ($parts[0] -eq '169' -and $parts[1] -eq '254'))
}

function Invoke-Compose([string[]]$DockerArguments) {
    & $script:Docker @script:ComposeArgs @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Commande Docker échouée. Vérifiez Docker, Internet, l’espace disque et le port 8770. Aucun volume supprimé.' }
}

function Test-Health {
    $state = & $script:Docker inspect --format '{{.State.Health.Status}}' pepper-brain 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -ne 'healthy') { return $false }
    # Bypass system proxies for localhost; a proxy response cannot mark Pepper ready.
    $request = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:8770/api/health')
    $request.Proxy = $null
    $request.Timeout = 2000
    $request.ReadWriteTimeout = 2000
    $response = $null
    $reader = $null
    try {
        $response = $request.GetResponse()
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $body = $reader.ReadToEnd() | ConvertFrom-Json
        return ($body.status -eq 'ok')
    } catch { return $false }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Show-Addresses {
    Write-Host "`nAdministration sur cet ordinateur : http://localhost:8770/"
    if ($LanIp) {
        Write-Host "Adresse LAN choisie manuellement : http://${LanIp}:8770"
        Write-Host 'Vérifiez que cette IPv4 appartient à cet ordinateur sur le réseau du robot.'
        return
    }
    $candidates = @()
    try {
        foreach ($adapter in @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up')) {
            foreach ($address in @(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue)) {
                if ($address.AddressState -eq 'Preferred' -and (Test-LanIp $address.IPAddress)) {
                    $candidates += [pscustomobject]@{ Name = $adapter.Name; IP = $address.IPAddress }
                }
            }
        }
    } catch { Write-Host 'Détection des cartes réseau indisponible.' }
    if ($candidates.Count -eq 1) {
        Write-Host "Réseau physique $($candidates[0].Name) : http://$($candidates[0].IP):8770"
    } elseif ($candidates.Count -gt 1) {
        Write-Host 'Plusieurs réseaux physiques : choisissez celui du robot.'
        for ($i = 0; $i -lt $candidates.Count; $i++) {
            Write-Host "$($i + 1)) $($candidates[$i].Name) — $($candidates[$i].IP)"
        }
        if ($script:Interactive) {
            $choice = Read-Host 'Numéro (Entrée pour choisir plus tard)'
            if ($choice -match '^[1-9][0-9]{0,2}$' -and [int]$choice -le $candidates.Count) {
                Write-Host "Adresse LAN : http://$($candidates[[int]$choice - 1].IP):8770"
                return
            }
        }
        Write-Host 'Aucune adresse choisie. Relancez avec -LanIp ADRESSE_IPV4.'
    } else {
        Write-Host 'Aucun réseau physique IPv4 identifié. Connectez le Wi-Fi/Ethernet ou utilisez -LanIp ADRESSE_IPV4 (voir le guide).'
    }
}

function Show-AdminToken {
    Write-Host 'Le jeton administrateur donne accès aux réglages et clés du client.'
    $answer = Read-Host 'Afficher ce jeton uniquement dans cette console locale privée et non enregistrée ? [o/N]'
    if ($answer -ne 'o') { return }
    # Capture native stdout in memory, never emit the token to a PowerShell stream,
    # transcript, URL, clipboard or file. Direct console display requires consent.
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $script:Docker
    $process.StartInfo.Arguments = 'exec pepper-brain cat /data/admin.token'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $secret = $null
    try {
        [void]$process.Start()
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            throw 'Lecture du jeton trop longue. Relancez setup après vérification de Docker.'
        }
        if ($process.ExitCode -ne 0) { throw 'Jeton indisponible ; vérifiez le serveur puis relancez setup.' }
        $secret = $output.GetAwaiter().GetResult().Trim()
        [Console]::WriteLine("`nJeton administrateur (à coller dans la webapp) :")
        [Console]::WriteLine($secret)
        [Console]::WriteLine()
    } finally {
        $secret = $null
        $process.Dispose()
    }
}

try {
    if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
        throw 'Utilisez Windows 64 bits avec Docker Desktop ; sur Mac/Linux, utilisez pepper.sh.'
    }
    if ($LanIp -and -not (Test-LanIp $LanIp)) { throw 'Adresse LAN IPv4 invalide (pas de localhost, multicast ou lien local).' }
    $script:Interactive = [Environment]::UserInteractive -and $Host.Name -eq 'ConsoleHost' -and
        -not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected -and -not [Console]::IsErrorRedirected
    if ($Action -eq 'setup' -and (-not $script:Interactive -or $env:SSH_CONNECTION -or $env:SSH_TTY -or
        (Test-Path variable:PSSenderInfo) -or $env:SESSIONNAME -like 'RDP*')) {
        throw 'setup exige une console interactive locale, sans redirection ni session distante. Utilisez start sans jeton.'
    }
    $command = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue
    if (-not $command) { throw 'Docker absent. Installez Docker Desktop, ouvrez-le puis relancez Pepper.cmd.' }
    $script:Docker = $command.Source
    if ($env:DOCKER_CONTEXT -or -not $env:DOCKER_HOST) {
        $endpoint = & $script:Docker context inspect --format '{{.Endpoints.docker.Host}}' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Contexte Docker introuvable. Sélectionnez un contexte Docker Desktop local.' }
    } else { $endpoint = $env:DOCKER_HOST }
    if ($endpoint -notlike 'npipe:////./pipe/*') { throw 'Le contexte Docker doit être local (Docker Desktop, canal nommé Windows). Aucun moteur distant ou TCP accepté.' }
    $engine = & $script:Docker info --format '{{.OSType}}/{{.Architecture}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Docker ne répond pas. Ouvrez Docker Desktop et attendez son démarrage.' }
    if ($engine -notmatch '^linux/(amd64|x86_64|arm64|aarch64)$') { throw 'Passez Docker Desktop en mode conteneurs Linux 64 bits.' }
    & $script:Docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose manque. Mettez Docker Desktop à jour.' }
    $brain = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../server/brain')).Path
    $script:ComposeArgs = @('compose', '--project-directory', $brain, '--env-file',
        (Join-Path $PSScriptRoot 'compose.env'), '--project-name', 'brain', '--file', (Join-Path $brain 'docker-compose.yml'))
    $owner = & $script:Docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' pepper-brain 2>$null
    if ($LASTEXITCODE -eq 0 -and $owner -ne 'brain') { throw 'Le conteneur pepper-brain appartient à une autre installation. Faites vérifier son projet et ses volumes par le support.' }
    switch ($Action) {
        'check' { Write-Host 'Docker local Linux et Compose disponibles. Aucun conteneur modifié.'; exit 0 }
        'stop' { Invoke-Compose @('stop', 'brain'); Write-Host 'Pepper arrêté. Données et modèles conservés.'; exit 0 }
        'status' {
            Invoke-Compose @('ps', 'brain')
            if (-not (Test-Health)) { throw 'Pepper est arrêté ou pas encore prêt. Relancez start ou contactez le support.' }
            Write-Host 'Pepper répond à /api/health.'
            Show-Addresses
            exit 0
        }
    }
    $unsafe = Get-ChildItem -LiteralPath $brain -Force -Recurse | Where-Object {
        $_.FullName -notmatch '[\\/](\.venv|__pycache__)[\\/]' -and
        ($_.Name -match '\.(token|key|pem|enc)$' -or $_.Name -in @('.env', 'data', 'models', 'venv'))
    } | Select-Object -First 1
    if ($unsafe) { throw 'server/brain contient des données privées ou un environnement local. Demandez un ZIP client propre au support.' }
    Write-Host 'Construction locale de Pepper (Internet requis au premier lancement)…'
    Invoke-Compose @('build', 'brain')
    Invoke-Compose @('up', '-d', '--no-build', '--pull', 'never', 'brain')
    Write-Host "Attente du serveur (jusqu’à $WaitSeconds s, hors construction)…"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Health)) {
        if ($timer.Elapsed.TotalSeconds -ge $WaitSeconds) { throw 'Serveur non prêt dans le délai prévu. Utilisez status ou -WaitSeconds 600. Le conteneur et les données sont conservés.' }
        Start-Sleep -Seconds 1
    }
    Write-Host 'Pepper est prêt pour la configuration. Whisper peut encore télécharger son modèle.'
    Show-Addresses
    if ($Action -eq 'setup') {
        Show-AdminToken
        if (-not $Open) { $Open = (Read-Host 'Ouvrir l’administration dans le navigateur ? [o/N]') -eq 'o' }
    }
    if ($Open) {
        try { Start-Process 'http://localhost:8770/' }
        catch { Write-Host 'Ouvrez http://localhost:8770/ manuellement.' }
    }
    exit 0
} catch {
    # Never print process output or native exception dumps containing private data.
    [Console]::Error.WriteLine('Erreur : ' + $_.Exception.Message)
    exit 1
}
