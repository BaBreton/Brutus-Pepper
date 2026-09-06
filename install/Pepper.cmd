@echo off
setlocal
if "%~1"=="" (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Pepper.ps1" setup
) else (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Pepper.ps1" %*
)
set "PEPPER_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %PEPPER_RESULT%
