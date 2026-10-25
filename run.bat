@echo off
cd /d "C:\Users\dhruv\Downloads\EchoMusic-Desktop"
echo Starting Echo Music Desktop...
call gradlew.bat -g C:\Users\dhruv\Downloads\gradle-home :desktop:run
if %ERRORLEVEL% NEQ 0 (
    echo Error launching app!
    pause
)
