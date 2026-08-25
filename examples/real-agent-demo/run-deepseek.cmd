@echo off
setlocal
title Vortex DeepSeek Real Agent Demo

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-deepseek.ps1"
set "DEMO_EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%DEMO_EXIT_CODE%"=="0" (
  echo Demo failed with exit code %DEMO_EXIT_CODE%.
) else (
  echo Demo finished successfully.
)
pause
exit /b %DEMO_EXIT_CODE%
