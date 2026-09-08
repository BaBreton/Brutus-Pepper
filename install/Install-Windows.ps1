#requires -Version 5.1
<#
    Installation clé en main de l'antenne Pepper sur un PC Windows partagé.

    Ce poste n'est pas dédié à Pepper : il sert aussi à autre chose. Le script ne
    touche donc qu'aux trois choses sans lesquelles l'antenne ne peut pas
    fonctionner — Docker Desktop, son démarrage à l'ouverture de session, et une
    autorisation de pare-feu limitée au port 8770 sur le réseau privé — et il sait
    défaire chacune d'elles avec `remove`.

    Il ne s'élève pas en administrateur pour tout : Docker Desktop et le dossier
    Démarrage appartiennent à la session ouverte, et les exécuter sous un autre
    compte administrateur installerait l'antenne au mauvais endroit. Seules les deux
    opérations qui l'exigent réellement — l'installation de Docker Desktop et la
    règle de pare-feu — demandent une élévation, une par une.

    Chaque étape est sans effet si elle est déjà faite : après un redémarrage exigé
    par Docker Desktop, relancer le script reprend là où il s'était arrêté.
#>
[CmdletBinding()]
param(
    [ValidateSet('install', 'status', 'remove')]
    [string]$Action = 'install',
    # Laisse le pare-feu inchangé : à utiliser si le service informatique du client
    # gère lui-même ses règles.
    [switch]$SkipFirewall,
    # Laisse le démarrage automatique inchangé.
    [switch]$SkipAutoStart,
    [ValidateRange(30, 3600)][int]$EngineWaitSeconds = 240
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# La console Windows par défaut n'est pas en UTF-8 : sans cela, les accents des
# messages arrivent en caractères illisibles chez le client. Échoue sans bruit sur
# les hôtes qui ne l'autorisent pas ; un message mal accentué reste lisible.
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$FirewallRuleName = 'Pepper antenne (TCP 8770)'
$StartupLinkName = 'Docker Desktop (antenne Pepper).lnk'
$DockerDesktopExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'

# ── Sortie ──────────────────────────────────────────────────────────────────

function Write-Step([string]$Number, [string]$Title) {
    Write-Host ''
    Write-Host "[$Number] $Title" -ForegroundColor Cyan
}
function Write-Done([string]$Message) { Write-Host "   OK  $Message" -ForegroundColor Green }
function Write-Skip([string]$Message) { Write-Host "   --  $Message" -ForegroundColor DarkGray }
function Write-Warn([string]$Message) { Write-Host "   !   $Message" -ForegroundColor Yellow }

function Confirm-Change([string]$Question, [bool]$DefaultYes = $true) {
    $suffix = if ($DefaultYes) { '[O/n]' } else { '[o/N]' }
    $answer = Read-Host "   $Question $suffix"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $DefaultYes }
    return $answer.Trim().ToLowerInvariant() -in @('o', 'oui', 'y', 'yes')
}

# ── Élévation ponctuelle ────────────────────────────────────────────────────

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

<#
    Exécute un fragment PowerShell en administrateur, puis rend la main.

    Le fragment passe par -EncodedCommand : une commande construite par
    concaténation de guillemets finirait tôt ou tard par se casser sur un chemin
    contenant une apostrophe, et ce script tourne chez des clients dont on ne
    choisit pas les noms de dossiers.
#>
function Invoke-Elevated([string]$Script, [string]$Purpose) {
    if (Test-Administrator) {
        & ([scriptblock]::Create($Script))
        return $true
    }
    Write-Host "   Windows va demander une autorisation administrateur : $Purpose"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList @(
        '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded)
    if ($process.ExitCode -ne 0) {
        Write-Warn 'Autorisation refusée ou commande échouée. Cette étape reste à faire.'
        return $false
    }
    return $true
}

# ── Docker ──────────────────────────────────────────────────────────────────

function Get-DockerCommand {
    # Voir Pepper.ps1 : le PATH de Docker Desktop publie « docker.exe » et « docker ».
    return (@(Get-Command docker -CommandType Application -ErrorAction SilentlyContinue) |
        Select-Object -First 1)
}

<#
    Sous $ErrorActionPreference = 'Stop', PowerShell 5.1 lève sur toute écriture
    native dans stderr, même redirigée. « docker info » moteur arrêté écrit
    justement là : sans ce relâchement local, une simple vérification d'état ferait
    échouer l'installation au lieu de répondre « pas encore prêt ».
#>
function Test-DockerEngine {
    $docker = Get-DockerCommand
    if (-not $docker) { return $false }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $docker.Source info --format '{{.ServerVersion}}' *> $null
        return ($LASTEXITCODE -eq 0)
    } finally { $ErrorActionPreference = $previous }
}

function Test-Virtualization {
    # La seule panne que le logiciel ne peut pas rattraper : si la virtualisation est
    # désactivée dans le BIOS, Docker Desktop s'installera puis refusera de démarrer.
    # Mieux vaut le dire avant l'installation qu'après un redémarrage inutile.
    try {
        $system = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
        if ($system.HypervisorPresent) { return $true }
        $cpu = @(Get-CimInstance Win32_Processor -ErrorAction Stop)[0]
        return [bool]$cpu.VirtualizationFirmwareEnabled
    } catch { return $true }  # information indisponible : on ne bloque pas pour autant
}

function Install-DockerDesktop {
    if (Test-Path -LiteralPath $script:DockerDesktopExe) {
        Write-Skip 'Docker Desktop est déjà installé.'
        return $true
    }
    $winget = Get-Command winget -CommandType Application -ErrorAction SilentlyContinue
    if (-not $winget) {
        Write-Warn 'winget est absent de ce Windows. Installez Docker Desktop à la main :'
        Write-Host '       https://www.docker.com/products/docker-desktop/'
        Write-Host '       Puis relancez ce script.'
        return $false
    }
    Write-Host '   Docker Desktop est absent. Il occupe environ 2 Go et demande Internet.'
    if (-not (Confirm-Change 'Installer Docker Desktop maintenant ?')) {
        Write-Skip "Installation refusée. L’antenne ne peut pas démarrer sans Docker."
        return $false
    }
    # 'Continue' et non 'Stop' : winget écrit sa progression sur stderr, et seul son
    # code de sortie dit si l'installation a réussi.
    $ok = Invoke-Elevated @'
$ErrorActionPreference = 'Continue'
winget install --id Docker.DockerDesktop --exact --silent `
    --accept-package-agreements --accept-source-agreements
exit $LASTEXITCODE
'@ 'installer Docker Desktop'
    if (-not $ok) { return $false }
    Write-Done 'Docker Desktop installé.'
    Write-Warn 'Windows demande souvent un redémarrage à ce stade (WSL 2).'
    Write-Host '       Redémarrez si Docker le demande, puis relancez Installer-Pepper.cmd.'
    return (Test-Path -LiteralPath $script:DockerDesktopExe)
}

function Start-DockerEngine([int]$TimeoutSeconds) {
    if (Test-DockerEngine) {
        Write-Skip 'Le moteur Docker répond déjà.'
        return $true
    }
    if (-not (Test-Path -LiteralPath $script:DockerDesktopExe)) { return $false }
    Write-Host '   Démarrage de Docker Desktop, puis attente du moteur…'
    Start-Process -FilePath $script:DockerDesktopExe | Out-Null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 3
        if (Test-DockerEngine) { return $true }
    }
    return $false
}

# ── Démarrage automatique ───────────────────────────────────────────────────

function Get-StartupLinkPath {
    return (Join-Path ([Environment]::GetFolderPath('Startup')) $script:StartupLinkName)
}

<#
    Un raccourci dans le dossier Démarrage de la session, pas un service ni une
    clé de registre : le conteneur porte déjà `restart: unless-stopped`, il ne lui
    manque que Docker lancé. Un raccourci se voit, se supprime d'un clic droit, et
    n'engage rien pour les autres usages du poste.
#>
function Enable-DockerAutoStart {
    $link = Get-StartupLinkPath
    if (Test-Path -LiteralPath $link) {
        Write-Skip 'Le démarrage automatique est déjà en place.'
        return
    }
    if (-not (Test-Path -LiteralPath $script:DockerDesktopExe)) {
        Write-Warn 'Docker Desktop introuvable : démarrage automatique non configuré.'
        return
    }
    Write-Host "   Sans cela, l’antenne reste éteinte après chaque redémarrage du poste"
    Write-Host "   tant que personne n’ouvre Docker Desktop à la main."
    if (-not (Confirm-Change "Lancer Docker Desktop à l’ouverture de votre session ?")) {
        Write-Skip 'Démarrage automatique non configuré.'
        return
    }
    $shell = New-Object -ComObject WScript.Shell
    try {
        $shortcut = $shell.CreateShortcut($link)
        $shortcut.TargetPath = $script:DockerDesktopExe
        $shortcut.WorkingDirectory = Split-Path -Parent $script:DockerDesktopExe
        $shortcut.Description = "Démarre Docker Desktop pour que l’antenne Pepper reparte seule."
        $shortcut.Save()
    } finally {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell)
    }
    Write-Done "Raccourci créé : $link"
}

function Disable-DockerAutoStart {
    $link = Get-StartupLinkPath
    if (-not (Test-Path -LiteralPath $link)) {
        Write-Skip 'Aucun démarrage automatique installé par ce script.'
        return
    }
    Remove-Item -LiteralPath $link -Force
    Write-Done 'Démarrage automatique retiré. Docker Desktop reste installé.'
}

# ── Pare-feu ────────────────────────────────────────────────────────────────

function Test-FirewallRule {
    return [bool](Get-NetFirewallRule -DisplayName $script:FirewallRuleName -ErrorAction SilentlyContinue)
}

function Show-NetworkProfile {
    # Un profil « Public » bloque les connexions entrantes quelle que soit la règle :
    # c'est la première cause de tablette qui ne trouve pas l'antenne.
    try {
        $public = @(Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -eq 'Public' })
    } catch { return }
    if (-not $public) { return }
    Write-Warn 'Un réseau de ce poste est classé « Public » :'
    foreach ($profile in $public) { Write-Host "       $($profile.Name) ($($profile.InterfaceAlias))" }
    Write-Host "       Tant qu’il l’est, Windows refuse les connexions entrantes du robot."
    Write-Host '       Dans Paramètres → Réseau, passez le réseau du robot en « Privé ».'
}

function Enable-FirewallRule {
    if (Test-FirewallRule) {
        Write-Skip "L’autorisation de pare-feu existe déjà."
        return
    }
    Write-Host '   La tablette de Pepper doit joindre ce poste sur le port TCP 8770.'
    Write-Host "   L’autorisation reste limitée au profil « Privé » : rien n’est ouvert"
    Write-Host '   sur un réseau public ni sur Internet.'
    if (-not (Confirm-Change 'Créer cette autorisation de pare-feu ?')) {
        Write-Skip "Pare-feu inchangé. Le robot ne pourra pas joindre l’antenne."
        return
    }
    $script = @"
`$ErrorActionPreference = 'Stop'
if (-not (Get-NetFirewallRule -DisplayName '$script:FirewallRuleName' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName '$script:FirewallRuleName' -Direction Inbound -Action Allow ``
        -Protocol TCP -LocalPort 8770 -Profile Private ``
        -Description 'Laisse la tablette Pepper joindre l''antenne sur le reseau prive.' | Out-Null
}
exit 0
"@
    if (Invoke-Elevated $script 'autoriser le port 8770 sur le réseau privé') {
        Write-Done 'Port 8770 autorisé sur le réseau privé.'
    }
}

function Remove-FirewallRule {
    if (-not (Test-FirewallRule)) {
        Write-Skip 'Aucune règle de pare-feu installée par ce script.'
        return
    }
    $script = @"
`$ErrorActionPreference = 'Stop'
Get-NetFirewallRule -DisplayName '$script:FirewallRuleName' -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule
exit 0
"@
    if (Invoke-Elevated $script "retirer l’autorisation du port 8770") {
        Write-Done 'Autorisation de pare-feu retirée.'
    }
}

# ── Actions ─────────────────────────────────────────────────────────────────

function Invoke-PepperLauncher([string]$LauncherAction) {
    $launcher = Join-Path $PSScriptRoot 'Pepper.ps1'
    if (-not (Test-Path -LiteralPath $launcher)) {
        throw 'Pepper.ps1 est introuvable à côté de ce script. Réextrayez le dossier livré sans le réorganiser.'
    }
    & $launcher $LauncherAction
    return ($LASTEXITCODE -eq 0)
}

function Show-Status {
    Write-Step '1/3' 'Docker'
    if (Test-DockerEngine) { Write-Done 'Le moteur Docker répond.' }
    elseif (Test-Path -LiteralPath $script:DockerDesktopExe) { Write-Warn 'Docker Desktop est installé mais arrêté.' }
    else { Write-Warn "Docker Desktop n’est pas installé." }

    Write-Step '2/3' 'Démarrage automatique'
    if (Test-Path -LiteralPath (Get-StartupLinkPath)) { Write-Done 'Docker Desktop démarre avec votre session.' }
    else { Write-Warn "Docker Desktop ne démarre pas tout seul : l’antenne restera éteinte après un redémarrage." }

    Write-Step '3/3' 'Pare-feu'
    if (Test-FirewallRule) { Write-Done 'Le port 8770 est autorisé sur le réseau privé.' }
    else { Write-Warn 'Aucune autorisation pour le port 8770.' }
    Show-NetworkProfile

    if (Test-DockerEngine) {
        Write-Host ''
        [void](Invoke-PepperLauncher 'status')
    }
}

function Invoke-Removal {
    Write-Host ''
    Write-Host 'Retrait des réglages posés par ce script. Ni Docker Desktop, ni les'
    Write-Host 'réglages de Pepper, ni la médiathèque ne sont supprimés.'
    Write-Step '1/3' "Arrêt de l’antenne"
    if (Test-DockerEngine) {
        if (Invoke-PepperLauncher 'stop') { Write-Done 'Antenne arrêtée, données conservées.' }
    } else { Write-Skip 'Moteur Docker arrêté : rien à arrêter.' }
    Write-Step '2/3' 'Démarrage automatique'
    Disable-DockerAutoStart
    Write-Step '3/3' 'Pare-feu'
    Remove-FirewallRule
    Write-Host ''
    Write-Host 'Terminé. Pour désinstaller Docker Desktop lui-même, passez par'
    Write-Host 'Paramètres → Applications.'
}

function Invoke-Installation {
    Write-Host ''
    Write-Host "Installation de l’antenne Pepper sur ce poste."
    Write-Host "Trois choses seront modifiées, et rien d’autre :"
    Write-Host "  1. Docker Desktop, installé s’il est absent ;"
    Write-Host "  2. un raccourci de démarrage, pour que l’antenne reparte après un redémarrage ;"
    Write-Host '  3. une autorisation de pare-feu pour le port 8770, sur le réseau privé seulement.'
    Write-Host 'Chacune vous sera demandée, et « Installer-Pepper.cmd remove » les défait.'

    if (-not (Test-Virtualization)) {
        Write-Warn 'La virtualisation matérielle semble désactivée dans le BIOS/UEFI.'
        Write-Host "       Docker Desktop ne pourra pas démarrer tant qu’elle le sera."
        Write-Host '       Activez « Intel VT-x » / « AMD-V » (ou « SVM ») dans le BIOS du poste.'
        if (-not (Confirm-Change 'Continuer quand même ?' $false)) { return 1 }
    }

    Write-Step '1/4' 'Docker Desktop'
    if (-not (Install-DockerDesktop)) { return 1 }
    if (-not (Start-DockerEngine $EngineWaitSeconds)) {
        Write-Warn "Le moteur Docker n’a pas répondu dans le délai prévu."
        Write-Host "       Ouvrez Docker Desktop, attendez que la baleine cesse de s’animer,"
        Write-Host "       acceptez ses conditions si c’est le premier lancement, puis relancez ce script."
        return 1
    }
    Write-Done 'Le moteur Docker répond.'

    Write-Step '2/4' 'Démarrage automatique'
    if ($SkipAutoStart) { Write-Skip 'Ignoré à votre demande (-SkipAutoStart).' }
    else { Enable-DockerAutoStart }

    Write-Step '3/4' 'Pare-feu'
    if ($SkipFirewall) { Write-Skip 'Ignoré à votre demande (-SkipFirewall).' }
    else { Enable-FirewallRule }
    Show-NetworkProfile

    Write-Step '4/4' "Construction et démarrage de l’antenne"
    Write-Host '   La première construction télécharge plusieurs centaines de Mo'
    Write-Host '   et peut prendre une dizaine de minutes.'
    if (-not (Invoke-PepperLauncher 'setup')) {
        Write-Warn "L’antenne n’a pas démarré. Le message ci-dessus indique quoi corriger."
        return 1
    }

    Write-Host ''
    Write-Host '[fin] Installation terminee' -ForegroundColor Cyan
    Write-Host 'Sur ce poste : http://localhost:8770/ — collez-y le jeton administrateur.'
    Write-Host "Sur la tablette de Pepper, rubrique « Cerveau » : l’adresse LAN affichée"
    Write-Host "plus haut, et le jeton d’appairage donné par la page « Connecter Pepper »."
    Write-Host 'Les deux appareils doivent être sur le même réseau.'
    Write-Host ''
    Write-Host 'État à tout moment  : Installer-Pepper.cmd status'
    Write-Host 'Tout défaire        : Installer-Pepper.cmd remove'
    return 0
}

# ── Point d'entrée ──────────────────────────────────────────────────────────

try {
    if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
        throw 'Ce script demande Windows 64 bits. Sur Mac ou Linux, utilisez install/pepper.sh.'
    }
    $interactive = [Environment]::UserInteractive -and $Host.Name -eq 'ConsoleHost' -and
        -not [Console]::IsInputRedirected
    if ($Action -ne 'status' -and -not $interactive) {
        throw "Lancez Installer-Pepper.cmd depuis le bureau de ce poste : l’installation pose des questions et affiche un jeton."
    }
    switch ($Action) {
        'status' { Show-Status; exit 0 }
        'remove' { Invoke-Removal; exit 0 }
        default  { exit (Invoke-Installation) }
    }
} catch {
    # Comme dans Pepper.ps1 : on ne relaie que le message rédigé, jamais une trace
    # native qui pourrait contenir un chemin ou une donnée du client.
    [Console]::Error.WriteLine('Erreur : ' + $_.Exception.Message)
    exit 1
}
