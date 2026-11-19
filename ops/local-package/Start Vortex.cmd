@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "vortex_exit=%errorlevel%"
if not "%vortex_exit%"=="0" if not defined VORTEX_NO_PAUSE pause
exit /b %vortex_exit%
