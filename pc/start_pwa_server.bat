@echo off
title M328V6 PWA Host Server
echo ========================================================
echo   M328V6 Web Host - Local PWA Launcher
echo   Click [Install app] in Chrome/Edge address bar
echo ========================================================
echo.

cd /d "%~dp0"

echo Opening browser at http://localhost:8328/m328v6_web_host.html ...
start "" "http://localhost:8328/m328v6_web_host.html"

echo.
echo Starting local HTTP server on port 8328...
echo Press Ctrl+C or close this window to stop the server.
echo (Installed PWA apps can run offline afterwards.)
echo.

python -m http.server 8328

if errorlevel 1 (
    echo.
    echo [ERROR] Python HTTP server stopped or Python is not found.
    echo You can also open m328v6_web_host.html directly in Chrome or Edge.
    echo.
    pause
)
