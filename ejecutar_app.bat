@echo off
title LactoSense - Compilar, Instalar y Arrancar
color 0B
echo ========================================================
echo        LACTOSENSE - ARRANQUE Y DEBUG EN ANDROID
echo ========================================================
echo.

set "ADB_EXE=adb"
where adb >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "C:\Users\eberj\AppData\Local\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_EXE=C:\Users\eberj\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )
)

echo [1/4] Verificando conexion con telefono / emulador...
"%ADB_EXE%" devices

echo.
echo [2/4] Compilando e instalando APK en el dispositivo...
call gradlew.bat installDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] La compilacion o instalacion fallo. Revisa los errores arriba.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/4] Iniciando LactoSense MainActivity...
"%ADB_EXE%" shell am start -n com.arduino.bluetooth/.MainActivity

echo.
echo [4/4] Mostrando Logs de Bluetooth en vivo (Presiona Ctrl+C para salir)...
echo ========================================================
"%ADB_EXE%" logcat -v time -s BluetoothService:V MainActivity:V *:E

pause
