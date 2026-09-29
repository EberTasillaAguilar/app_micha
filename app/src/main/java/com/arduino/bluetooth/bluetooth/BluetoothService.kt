package com.arduino.bluetooth.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothService"
        // Standard SerialPortProfile (SPP) UUID para HC-05
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val STATE_NONE = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCONNECTED = 3

        private const val HEADER_START = "<<<START_FILE:"
        private const val HEADER_END_TAG = ">>>"
        private const val FOOTER_TAG = "<<<END_FILE>>>"
    }

    interface BluetoothEventListener {
        fun onStateChanged(state: Int, deviceName: String?)
        fun onDataReceived(data: ByteArray, length: Int, asText: String)
        fun onAutoFileReceived(file: File, fileName: String, totalBytes: Long)
        fun onError(errorMessage: String)
    }

    var listener: BluetoothEventListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private var currentState = STATE_NONE

    @Synchronized
    fun getState(): Int = currentState

    @Synchronized
    private fun setState(state: Int, deviceName: String? = null) {
        currentState = state
        mainHandler.post {
            listener?.onStateChanged(state, deviceName)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        // Cancelar cualquier conexión en curso
        if (currentState == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }

        val name = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

        setState(STATE_CONNECTING, name)

        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    @Synchronized
    fun disconnect() {
        connectThread?.cancel()
        connectThread = null

        connectedThread?.cancel()
        connectedThread = null

        setState(STATE_DISCONNECTED)
    }

    fun write(data: ByteArray) {
        var r: ConnectedThread?
        synchronized(this) {
            if (currentState != STATE_CONNECTED) return
            r = connectedThread
        }
        r?.write(data)
    }

    private inner class ConnectThread(private val mmDevice: BluetoothDevice) : Thread() {
        private var mmSocket: BluetoothSocket? = null
        private var fallbackSocket: BluetoothSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val bluetoothAdapter = bluetoothManager?.adapter
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "cancelDiscovery failed", e)
            }

            var socket: BluetoothSocket? = null
            var lastException: Exception? = null

            // Intento 1: Insecure RFCOMM Socket (el más compatible con módulos HC-05 / SPP)
            try {
                Log.d(TAG, "Attempting createInsecureRfcommSocketToServiceRecord...")
                socket = mmDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                connected(socket, mmDevice)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Insecure socket failed: ${e.message}, trying standard secure socket...")
                lastException = e
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
            }

            // Intento 2: Secure RFCOMM Socket estándar
            try {
                Log.d(TAG, "Attempting createRfcommSocketToServiceRecord...")
                socket = mmDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                connected(socket, mmDevice)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Secure socket failed: ${e.message}, trying reflection channel 1...")
                lastException = e
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
            }

            // Intento 3: Reflection Channel 1 (Insecure)
            try {
                Log.d(TAG, "Attempting reflection createInsecureRfcommSocket(1)...")
                val clazz = mmDevice.javaClass
                val paramTypes = arrayOf<Class<*>>(Integer.TYPE)
                val method = clazz.getMethod("createInsecureRfcommSocket", *paramTypes)
                socket = method.invoke(mmDevice, 1) as BluetoothSocket
                socket.connect()
                connected(socket, mmDevice)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Reflection insecure failed: ${e.message}, trying reflection secure channel 1...")
                lastException = e
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
            }

            // Intento 4: Reflection Channel 1 (Standard)
            try {
                Log.d(TAG, "Attempting reflection createRfcommSocket(1)...")
                val clazz = mmDevice.javaClass
                val paramTypes = arrayOf<Class<*>>(Integer.TYPE)
                val method = clazz.getMethod("createRfcommSocket", *paramTypes)
                socket = method.invoke(mmDevice, 1) as BluetoothSocket
                socket.connect()
                connected(socket, mmDevice)
                return
            } catch (e: Exception) {
                Log.e(TAG, "All connection attempts failed", e)
                lastException = e
                try { socket?.close() } catch (ex: Exception) {}
            }

            setState(STATE_DISCONNECTED)
            val errorMsg = lastException?.message ?: "Error desconocido al enlazar socket"
            mainHandler.post {
                listener?.onError("No se pudo conectar al HC-05 ($errorMsg). Verifica alimentación (5V) y que el LED parpadee rápido.")
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Close of connect socket failed", e)
            }
            try {
                fallbackSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Close of fallback socket failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        connectThread?.cancel()
        connectThread = null

        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }

        val devName = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

        connectedThread = ConnectedThread(socket, devName)
        connectedThread?.start()

        setState(STATE_CONNECTED, devName)
    }

    private inner class ConnectedThread(
        private val mmSocket: BluetoothSocket,
        private val deviceName: String
    ) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream

        // Protocol parser buffer for automatic file reception
        private var isReceivingProtocolFile = false
        private var protocolFileName = ""
        private var protocolFileStream: FileOutputStream? = null
        private var protocolCurrentFile: File? = null
        private var protocolBytesReceived: Long = 0
        private val stringBuilder = StringBuilder()

        override fun run() {
            val buffer = ByteArray(2048)
            var bytes: Int

            while (currentState == STATE_CONNECTED) {
                try {
                    bytes = mmInStream.read(buffer)
                    if (bytes > 0) {
                        val chunk = buffer.copyOf(bytes)
                        val text = String(chunk, Charsets.UTF_8)

                        // Process automatic file protocol
                        processProtocolChunks(chunk, bytes, text)

                        mainHandler.post {
                            listener?.onDataReceived(chunk, bytes, text)
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Input stream read disconnected", e)
                    if (currentState == STATE_CONNECTED) {
                        setState(STATE_DISCONNECTED)
                        mainHandler.post {
                            listener?.onError("Conexión perdida con el módulo HC-05. Verifica alimentación de 5V y cercanía.")
                        }
                    }
                    break
                }
            }
        }

        private fun processProtocolChunks(rawBytes: ByteArray, length: Int, text: String) {
            try {
                stringBuilder.append(text)
                val currentText = stringBuilder.toString()

                if (!isReceivingProtocolFile) {
                    if (currentText.contains(HEADER_START) && currentText.contains(HEADER_END_TAG)) {
                        val startIndex = currentText.indexOf(HEADER_START)
                        val endIndex = currentText.indexOf(HEADER_END_TAG, startIndex)

                        if (endIndex > startIndex) {
                            val header = currentText.substring(startIndex + HEADER_START.length, endIndex).trim()
                            protocolFileName = if (header.isNotEmpty()) header else "radio_transfer_${System.currentTimeMillis()}.dat"

                            val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                            if (docsDir != null && !docsDir.exists()) {
                                docsDir.mkdirs()
                            }

                            protocolCurrentFile = File(docsDir, protocolFileName)
                            protocolFileStream = FileOutputStream(protocolCurrentFile)
                            protocolBytesReceived = 0
                            isReceivingProtocolFile = true

                            // Remaining data after header
                            val remainingText = currentText.substring(endIndex + HEADER_END_TAG.length)
                            stringBuilder.setLength(0)

                            if (remainingText.isNotEmpty()) {
                                val remainingBytes = remainingText.toByteArray(Charsets.UTF_8)
                                protocolFileStream?.write(remainingBytes)
                                protocolBytesReceived += remainingBytes.size
                                stringBuilder.append(remainingText)
                            }
                        }
                    }
                } else {
                    // We are receiving file data
                    if (currentText.contains(FOOTER_TAG)) {
                        val footerIndex = currentText.indexOf(FOOTER_TAG)
                        // Write everything up to footer
                        val dataBeforeFooter = currentText.substring(0, footerIndex)
                        if (dataBeforeFooter.isNotEmpty()) {
                            val finalBytes = dataBeforeFooter.toByteArray(Charsets.UTF_8)
                            protocolFileStream?.write(finalBytes)
                            protocolBytesReceived += finalBytes.size
                        }
                        
                        protocolFileStream?.flush()
                        protocolFileStream?.close()
                        protocolFileStream = null

                        val finalFile = protocolCurrentFile
                        val totalBytes = protocolBytesReceived

                        isReceivingProtocolFile = false
                        stringBuilder.setLength(0)

                        if (finalFile != null) {
                            mainHandler.post {
                                listener?.onAutoFileReceived(finalFile, protocolFileName, totalBytes)
                            }
                        }
                    } else {
                        // Write raw chunk to file
                        protocolFileStream?.write(rawBytes, 0, length)
                        protocolBytesReceived += length

                        // Keep buffer short to prevent unbounded memory
                        if (stringBuilder.length > 500) {
                            stringBuilder.delete(0, stringBuilder.length - 200)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing protocol file", e)
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing data to output stream", e)
            }
        }

        fun cancel() {
            try {
                protocolFileStream?.close()
            } catch (e: Exception) {}
            try {
                mmInStream.close()
            } catch (e: Exception) {}
            try {
                mmOutStream.close()
            } catch (e: Exception) {}
            try {
                mmSocket.close()
            } catch (e: Exception) {}
        }
    }
}
