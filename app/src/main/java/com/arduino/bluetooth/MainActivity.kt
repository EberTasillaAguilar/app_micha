package com.arduino.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arduino.bluetooth.adapters.DeviceAdapter
import com.arduino.bluetooth.adapters.FilesAdapter
import com.arduino.bluetooth.bluetooth.BluetoothService
import com.arduino.bluetooth.databinding.ActivityMainBinding
import com.arduino.bluetooth.databinding.DialogSaveFileBinding
import com.arduino.bluetooth.models.BluetoothDeviceInfo
import com.arduino.bluetooth.models.SavedFileInfo
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity(), BluetoothService.BluetoothEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothService: BluetoothService
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val rawDataBuffer = ByteArrayOutputStream()
    private val lineBuffer = StringBuilder()
    private var totalBytesReceived: Long = 0
    private var totalSamplesCount: Int = 0

    // Current Milk Sensor State (3 metrics: Temp, pH, Densidad simulada)
    private var currentTemp: Double? = null
    private var currentPh: Double? = null
    private var simulatedDensity: Double = 1.0302

    // Dynamic Simulation Handler for Density (Actualiza rápido cada 600ms)
    private val simulationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val simulationRunnable = object : Runnable {
        override fun run() {
            advanceSimulatedMetrics()
            simulationHandler.postDelayed(this, 600)
        }
    }

    // Permission launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothEnabledAndShowDevices()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de Bluetooth para conectar con el módulo HC-05",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Enable bluetooth launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showDeviceSelectionDialog()
        } else {
            Toast.makeText(this, "El Bluetooth debe estar activado para conectarse al HC-05", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        bluetoothService = BluetoothService(this)
        bluetoothService.listener = this

        setupUI()
        updateDashboardUI()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        binding.btnDisconnect.setOnClickListener {
            bluetoothService.disconnect()
        }

        binding.btnClearTerminal.setOnClickListener {
            rawDataBuffer.reset()
            lineBuffer.setLength(0)
            binding.tvTerminalOutput.text = ""
            updateByteCounter(0)
        }

        binding.btnSaveFile.setOnClickListener {
            showSaveFileDialog()
        }

        binding.btnViewFiles.setOnClickListener {
            showSavedFilesBottomSheet()
        }
    }

    private fun checkPermissionsAndConnect() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo no soporta Bluetooth", Toast.LENGTH_LONG).show()
            return
        }

        // 1. Comprobar primero los permisos en tiempo de ejecución (En Android 12+ isEnabled lanza SecurityException si no hay permisos)
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        // 2. Con los permisos ya otorgados, verificar si el Bluetooth está encendido
        checkBluetoothEnabledAndShowDevices()
    }

    private fun checkBluetoothEnabledAndShowDevices() {
        try {
            if (bluetoothAdapter?.isEnabled == false) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
                return
            }
            showDeviceSelectionDialog()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permiso de Bluetooth no concedido: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error con el Bluetooth: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        try {
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val deviceList = mutableListOf<BluetoothDeviceInfo>()

            pairedDevices?.forEach { device ->
                val devName = try {
                    device.name ?: "Dispositivo Desconocido"
                } catch (e: SecurityException) {
                    "Dispositivo Bluetooth"
                }
                deviceList.add(BluetoothDeviceInfo(devName, device.address))
            }

            if (deviceList.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Sin dispositivos vinculados")
                    .setMessage("No se encontraron dispositivos vinculados. Por favor ve a los Ajustes de Bluetooth de tu teléfono y vincula tu módulo HC-05 (código usual: 1234 o 0000).")
                    .setPositiveButton("Abrir Ajustes BT") { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Abre Ajustes > Bluetooth en tu teléfono", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
                return
            }

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_devices_list, null)
            val rvDevices = dialogView.findViewById<RecyclerView>(R.id.rvDevices)
            val btnClose = dialogView.findViewById<View>(R.id.btnCloseDevices)
            val btnOpenBtSettings = dialogView.findViewById<View>(R.id.btnOpenBtSettings)

            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            val adapter = DeviceAdapter(deviceList) { selectedDevice ->
                dialog.dismiss()
                connectToDevice(selectedDevice.address)
            }

            rvDevices.layoutManager = LinearLayoutManager(this)
            rvDevices.adapter = adapter
            btnClose.setOnClickListener { dialog.dismiss() }
            btnOpenBtSettings?.setOnClickListener {
                dialog.dismiss()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Abre Ajustes > Bluetooth en tu teléfono", Toast.LENGTH_SHORT).show()
                }
            }

            dialog.show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permiso de Bluetooth denegado: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al listar dispositivos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectToDevice(address: String) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device != null) {
                val devName = try {
                    device.name ?: "HC-05"
                } catch (e: SecurityException) {
                    "HC-05"
                }
                binding.tvDeviceName.text = devName
                binding.tvDeviceAddress.text = device.address
                bluetoothService.connect(device)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al conectar con el dispositivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStateChanged(state: Int, deviceName: String?) {
        when (state) {
            BluetoothService.STATE_NONE, BluetoothService.STATE_DISCONNECTED -> {
                binding.tvStatusBadge.text = getString(R.string.status_disconnected)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_disconnected)
                binding.btnConnect.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.GONE
                binding.tvDeviceSubtitle.text = "HC-05 Desconectado"
            }
            BluetoothService.STATE_CONNECTING -> {
                binding.tvStatusBadge.text = getString(R.string.status_connecting)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_connecting)
                binding.tvDeviceSubtitle.text = "Enlazando con $deviceName..."
            }
            BluetoothService.STATE_CONNECTED -> {
                binding.tvStatusBadge.text = getString(R.string.status_connected)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                binding.btnConnect.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
                binding.tvDeviceSubtitle.text = "Enlace activo: $deviceName"
                Toast.makeText(this, "Conectado a $deviceName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        simulationHandler.post(simulationRunnable)
    }

    override fun onPause() {
        super.onPause()
        simulationHandler.removeCallbacks(simulationRunnable)
    }

    private fun advanceSimulatedMetrics() {
        // Densidad: simulación activa continua con variación visible (1.028 a 1.034 g/ml)
        val baseTemp = currentTemp ?: 18.0
        val thermalOffset = (baseTemp - 15.0) * 0.00022
        
        // Paso dinámico aleatorio visible que se mueve fluidamente
        val step = Random.nextDouble(-0.0007, 0.0007)
        val newTarget = simulatedDensity + step
        simulatedDensity = newTarget.coerceIn(1.0280 - thermalOffset, 1.0336 - thermalOffset)
            .coerceIn(1.0272, 1.0342)

        updateDashboardUI()
    }

    override fun onDataReceived(data: ByteArray, length: Int, asText: String) {
        rawDataBuffer.write(data, 0, length)
        totalBytesReceived += length
        updateByteCounter(totalBytesReceived)

        binding.tvTerminalOutput.append(asText)
        binding.scrollTerminal.post {
            binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }

        // Process line buffer for milk sensors
        lineBuffer.append(asText)
        processIncomingLines()
    }

    private fun processIncomingLines() {
        while (lineBuffer.contains("\n")) {
            val newlineIndex = lineBuffer.indexOf("\n")
            val line = lineBuffer.substring(0, newlineIndex).trim()
            lineBuffer.delete(0, newlineIndex + 1)

            if (line.isNotEmpty()) {
                parseMilkTelemetryLine(line)
            }
        }
    }

    /**
     * Parsea formatos comunes enviados por Arduino/Radio:
     * - "TEMP:18.5,PH:6.68"
     * - "T=18.5,P=6.68"
     * - "18.5,6.68" o "18.5;6.68"
     * - {"temp":18.5,"ph":6.68}
     */
    private fun parseMilkTelemetryLine(line: String) {
        try {
            var tempFound: Double? = null
            var phFound: Double? = null

            val upper = line.uppercase()

            if (upper.contains("TEMP") || upper.contains("PH")) {
                // Key-value parsing
                val tokens = line.split(",", ";", " ", "|")
                for (token in tokens) {
                    val part = token.trim().uppercase()
                    if (part.startsWith("TEMP:") || part.startsWith("T:") || part.startsWith("TEMP=") || part.startsWith("T=")) {
                        val numStr = part.replace(Regex("[^0-9.]"), "")
                        tempFound = numStr.toDoubleOrNull()
                    } else if (part.startsWith("PH:") || part.startsWith("P:") || part.startsWith("PH=") || part.startsWith("P=")) {
                        val numStr = part.replace(Regex("[^0-9.]"), "")
                        phFound = numStr.toDoubleOrNull()
                    }
                }
            } else if (line.contains(",") || line.contains(";")) {
                // Comma / semicolon separated values: "22.5, 6.65"
                val parts = line.split(",", ";").map { it.trim() }
                if (parts.size >= 2) {
                    tempFound = parts[0].toDoubleOrNull()
                    phFound = parts[1].toDoubleOrNull()
                }
            } else if (line.startsWith("{") && line.endsWith("}")) {
                // JSON format
                val tempMatch = Regex("\"(?:temp|temperatura|t)\"\\s*:\\s*([0-9.]+)").find(line.lowercase())
                val phMatch = Regex("\"(?:ph|p)\"\\s*:\\s*([0-9.]+)").find(line.lowercase())
                tempFound = tempMatch?.groupValues?.get(1)?.toDoubleOrNull()
                phFound = phMatch?.groupValues?.get(1)?.toDoubleOrNull()
            }

            if (tempFound != null || phFound != null) {
                if (tempFound != null) currentTemp = tempFound
                if (phFound != null) currentPh = phFound

                // Simulate dynamic density based on new temp reading
                val t = currentTemp ?: 18.0
                val thermalOffset = (t - 15.0) * 0.00022
                val jitter = Random.nextDouble(-0.0004, 0.0004)
                simulatedDensity = (1.0305 - thermalOffset + jitter).coerceIn(1.0270, 1.0340)

                totalSamplesCount++
                updateDashboardUI()
            }
        } catch (e: Exception) {
            // Ignorar tramas no formateadas
        }
    }

    private fun updateDashboardUI() {
        val temp = currentTemp
        val ph = currentPh
        val density = simulatedDensity

        // Update Samples & Time
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvLastSampleTime.text = if (temp != null || ph != null) "Última muestra: $currentTime" else "Última muestra: En espera de radio..."
        binding.tvTotalSamples.text = "Muestras: $totalSamplesCount"

        // 1. Temperatura
        if (temp != null) {
            binding.tvTempValue.text = String.format(Locale.US, "%.1f °C", temp)
            when {
                temp in 3.0..7.0 -> {
                    binding.tvTempStatus.text = "Temperatura de refrigeración óptima (3-7°C)"
                    binding.ivTempIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_optimal))
                }
                temp in 7.1..20.0 -> {
                    binding.tvTempStatus.text = "Temperatura fresca de recepción (< 20°C)"
                    binding.ivTempIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
                }
                temp > 20.0 -> {
                    binding.tvTempStatus.text = "¡Alerta! Temperatura alta (Riesgo de acidificación >20°C)"
                    binding.ivTempIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_danger))
                }
                else -> {
                    binding.tvTempStatus.text = "Temperatura baja / congelamiento (< 3°C)"
                    binding.ivTempIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
                }
            }
        } else {
            binding.tvTempValue.text = "--.- °C"
            binding.tvTempStatus.text = "Esperando lectura de sensor..."
        }

        // 2. pH
        if (ph != null) {
            binding.tvPhValue.text = String.format(Locale.US, "%.2f", ph)
            when {
                ph in 6.60..6.80 -> {
                    binding.tvPhStatus.text = "pH Normal y Óptimo (6.60 - 6.80)"
                    binding.ivPhIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_optimal))
                }
                ph in 6.40..6.59 -> {
                    binding.tvPhStatus.text = "Leche ligeramente ácida (Inicio de fermentación)"
                    binding.ivPhIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
                }
                ph < 6.40 -> {
                    binding.tvPhStatus.text = "¡Alerta! Leche Ácida / Cortada (pH < 6.40)"
                    binding.ivPhIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_danger))
                }
                else -> {
                    binding.tvPhStatus.text = "Leche Alcalina (Posible mastitis o adulteración > 6.80)"
                    binding.ivPhIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
                }
            }
        } else {
            binding.tvPhValue.text = "--.--"
            binding.tvPhStatus.text = "Esperando lectura de pH..."
        }

        // 3. Densidad Relativa (Simulada Dinámica no estática)
        binding.tvDensityValue.text = String.format(Locale.US, "%.3f g/ml", density)
        when {
            density in 1.028..1.034 -> {
                binding.tvDensityStatus.text = "Densidad Estándar Normal (1.028 - 1.034 g/cm³ a 15°C)"
                binding.ivDensityIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_optimal))
            }
            density < 1.028 -> {
                binding.tvDensityStatus.text = "Densidad baja (Posible aguado de la leche)"
                binding.ivDensityIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
            }
            else -> {
                binding.tvDensityStatus.text = "Densidad alta (Posible descremado o sólidos)"
                binding.ivDensityIndicator.setColorFilter(ContextCompat.getColor(this, R.color.status_warning))
            }
        }

        // 4. Diagnóstico General de Calidad
        evaluateOverallMilkQuality(temp, ph, density)
    }

    private fun evaluateOverallMilkQuality(temp: Double?, ph: Double?, density: Double) {
        if (temp == null && ph == null) {
            binding.tvQualityBadge.text = "EN ESPERA"
            binding.tvQualityBadge.setBackgroundResource(R.drawable.bg_badge_connecting)
            binding.tvQualityTitle.text = "Esperando Transmisión de Radio"
            binding.tvQualityDescription.text = "Conecta el módulo HC-05 para recibir las mediciones en tiempo real."
            return
        }

        val isPhWarning = ph != null && ph in 6.40..6.59
        val isPhBad = ph != null && (ph < 6.40 || ph > 6.85)
        val isTempBad = temp != null && temp > 25.0
        val isDensityOk = density in 1.028..1.034

        when {
            isPhBad || (temp != null && temp > 30.0) -> {
                binding.tvQualityBadge.text = "NO APTA"
                binding.tvQualityBadge.setBackgroundResource(R.drawable.bg_badge_disconnected)
                binding.tvQualityTitle.text = "¡Alerta! Leche Fuera de Estándar"
                binding.tvQualityDescription.text = "Los parámetros de pH o temperatura indican acidez alta o riesgo inminente de descomposición láctea."
            }
            isPhWarning || isTempBad || !isDensityOk -> {
                binding.tvQualityBadge.text = "ADVERTENCIA"
                binding.tvQualityBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                binding.tvQualityTitle.text = "Calidad Regular / En Observación"
                binding.tvQualityDescription.text = "Uno o más parámetros se encuentran en los límites permitidos. Se recomienda enfriamiento inmediato."
            }
            else -> {
                binding.tvQualityBadge.text = "CALIDAD ÓPTIMA"
                binding.tvQualityBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                binding.tvQualityTitle.text = "Leche Fresca Apta para Procesamiento"
                binding.tvQualityDescription.text = "pH (6.6-6.8), temperatura y densidad en perfecto equilibrio físico-químico."
            }
        }
    }

    override fun onAutoFileReceived(file: File, fileName: String, totalBytes: Long) {
        AlertDialog.Builder(this)
            .setTitle("Archivo de Leche Recibido")
            .setMessage("Se ha completado la recepción del archivo:\n\n$fileName (${formatFileSize(totalBytes)})\n\nGuardado en Documentos.")
            .setPositiveButton("Ver Archivos") { _, _ ->
                showSavedFilesBottomSheet()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    override fun onError(errorMessage: String) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
    }

    private fun updateByteCounter(bytes: Long) {
        binding.tvBytesCount.text = formatFileSize(bytes)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun showSaveFileDialog() {
        val dialogBinding = DialogSaveFileBinding.inflate(layoutInflater)
        val defaultName = "leche_calidad_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
        dialogBinding.etFileName.setText(defaultName)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelSave.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmSave.setOnClickListener {
            val inputName = dialogBinding.etFileName.text.toString().trim()
            val fileName = if (inputName.isNotEmpty()) inputName else defaultName
            saveDataToFile(fileName)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveDataToFile(fileName: String) {
        try {
            val docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (docsDir != null && !docsDir.exists()) {
                docsDir.mkdirs()
            }

            val targetFile = File(docsDir, fileName)
            val fos = FileOutputStream(targetFile)

            // If raw buffer has content, save it. Otherwise, export current telemetry table
            if (rawDataBuffer.size() > 0) {
                fos.write(rawDataBuffer.toByteArray())
            } else {
                val csvContent = buildString {
                    append("Timestamp,Temperatura_C,pH,Densidad_g_cm3,Calidad\n")
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    append("$time,${currentTemp ?: 0.0},${currentPh ?: 0.0},${String.format(Locale.US, "%.3f", simulatedDensity)},${binding.tvQualityBadge.text}\n")
                }
                fos.write(csvContent.toByteArray(Charsets.UTF_8))
            }

            fos.flush()
            fos.close()

            Toast.makeText(this, "Guardado: ${targetFile.name} (${formatFileSize(targetFile.length())})", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar archivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavedFilesBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_files_list, null)
        bottomSheetDialog.setContentView(view)

        val rvFiles = view.findViewById<RecyclerView>(R.id.rvFiles)
        val tvEmpty = view.findViewById<View>(R.id.tvEmptyFiles)
        val btnClose = view.findViewById<View>(R.id.btnCloseFiles)

        val docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val files = docsDir?.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val fileItems = files.map { file ->
            SavedFileInfo(
                file = file,
                name = file.name,
                sizeText = formatFileSize(file.length()),
                dateText = dateFormat.format(Date(file.lastModified()))
            )
        }.toMutableList()

        if (fileItems.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvFiles.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvFiles.visibility = View.VISIBLE
        }

        var adapter: FilesAdapter? = null
        adapter = FilesAdapter(
            fileItems,
            onFileClick = { item -> openFileWithExternalApp(item.file) },
            onShareClick = { item -> shareFile(item.file) },
            onDeleteClick = { item, position ->
                AlertDialog.Builder(this)
                    .setTitle("Eliminar archivo")
                    .setMessage("¿Deseas eliminar '${item.name}'?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        if (item.file.delete()) {
                            adapter?.removeAt(position)
                            Toast.makeText(this, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )

        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
        btnClose.setOnClickListener { bottomSheetDialog.dismiss() }

        bottomSheetDialog.show()
    }

    private fun openFileWithExternalApp(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val mimeType = when {
                file.name.endsWith(".txt", true) -> "text/plain"
                file.name.endsWith(".csv", true) -> "text/comma-separated-values"
                file.name.endsWith(".json", true) -> "application/json"
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Abrir registro con..."))
        } catch (e: Exception) {
            Toast.makeText(this, "No se encontró app para abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Compartir datos de leche"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error al compartir archivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.disconnect()
    }
}
