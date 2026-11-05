@echo off
setlocal
cd /d "%~dp0"
echo Starting Echo Music Desktop...
call gradlew.bat :desktop:run
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Build failed. See BUILD.md for prerequisites.
    pause
)
