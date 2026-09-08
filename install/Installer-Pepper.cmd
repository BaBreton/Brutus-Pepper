@echo off
rem Point d'entree Windows : double-cliquez ce fichier.
rem Comme Pepper.cmd, l'autorisation d'execution est limitee a ce processus
rem PowerShell ; la strategie d'execution du poste n'est pas modifiee.
rem Sans accent : un .cmd est lu dans la page de codes OEM de la console.
setlocal
if "%~1"=="" (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Windows.ps1" install
) else (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Windows.ps1" %*
)
set "PEPPER_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %PEPPER_RESULT%
