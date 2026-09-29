@echo off
title Compilar APK LactoSense
color 0A
echo ========================================================
echo          COMPILANDO APK DEBUG DE LACTOSENSE
echo ========================================================
echo.

call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo  COMPILACION EXITOSA!
    echo  APK generado en: app\build\outputs\apk\debug\app-debug.apk
    echo ========================================================
    echo.
    if exist "app\build\outputs\apk\debug" (
        explorer "app\build\outputs\apk\debug"
    )
) else (
    echo.
    echo [ERROR] Hubo un error al compilar el APK.
)

pause
