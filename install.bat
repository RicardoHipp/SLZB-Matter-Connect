@echo off
set APK=\\wsl$\Ubuntu\home\ricardo\SLZB-Matter-Connect\matter-sdk\out\android-arm64-chip-tool\outputs\apk\debug\app-debug.apk

echo Suche verbundenes Handy...
adb devices

if not exist "%APK%" (
    echo.
    echo APK nicht gefunden: %APK%
    echo Bitte zuerst build.bat ausfuehren.
    pause
    exit /b 1
)

echo.
echo Installiere %APK% ...
adb install -r "%APK%"

pause
