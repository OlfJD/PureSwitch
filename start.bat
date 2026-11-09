@echo off
setlocal
title PureSwitch - Smart LAN Outlets
cd /d "%~dp0"

echo ===================================================
echo   PureSwitch - Smart LAN Outlets
echo   Local Dev Server ^& Web Preview
echo ===================================================
echo.

set "PATH=C:\Program Files\nodejs;%PATH%"

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js is not found in your PATH!
    echo Please install Node.js from https://nodejs.org/
    echo.
    pause
    exit /b 1
)

where npm >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] npm is not found in your PATH!
    echo.
    pause
    exit /b 1
)

if not exist "node_modules\" (
    echo [*] Installing dependencies...
    call npm.cmd install
    if %errorlevel% neq 0 (
        echo [ERROR] npm install failed!
        pause
        exit /b 1
    )
)

echo [*] Launching PureSwitch server...
echo [*] Opening http://localhost:5173/ in default browser...
echo.

start "" cmd /c "timeout /t 2 /nobreak >nul & start http://localhost:5173/"
call npm.cmd run dev

if %errorlevel% neq 0 (
    echo.
    echo [!] Server stopped with exit code %errorlevel%.
    pause
)
