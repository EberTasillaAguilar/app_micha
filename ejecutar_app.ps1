Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "       LACTOSENSE - ARRANQUE Y DEBUG EN ANDROID        " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Detectar ADB
$adbPath = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\Users\eberj\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($cand in $candidates) {
        if (Test-Path $cand) {
            $adbPath = $cand
            break
        }
    }
}

Write-Host "[1/4] Verificando conexion con telefono / emulador..." -ForegroundColor Yellow
& $adbPath devices

Write-Host ""
Write-Host "[2/4] Compilando e instalando APK en el dispositivo..." -ForegroundColor Yellow
$gradleCmd = ".\gradlew.bat"
& $gradleCmd installDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[ERROR] La compilacion o instalacion fallo." -ForegroundColor Red
    Write-Host "Asegurate de tener el telefono conectado con depuracion USB o un emulador abierto." -ForegroundColor Yellow
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[3/4] Iniciando LactoSense MainActivity..." -ForegroundColor Green
& $adbPath shell am start -n com.arduino.bluetooth/.MainActivity

Write-Host ""
Write-Host "[4/4] Mostrando Logs de Bluetooth en vivo (Presiona Ctrl+C para salir)..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
& $adbPath logcat -v time -s BluetoothService:V MainActivity:V *:E
