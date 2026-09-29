/*
 * ============================================================================
 * LACTOSENSE: Receptor Bluetooth HC-05 (Calidad de la Leche)
 * ============================================================================
 * 
 * Conexión HC-05 con Arduino:
 *   - VCC -> 5V de Arduino
 *   - GND -> GND de Arduino
 *   - TXD (HC-05) -> Pin 10 de Arduino (RX)
 *   - RXD (HC-05) -> Pin 11 de Arduino (TX con divisor de voltaje)
 */

#include <SoftwareSerial.h>

SoftwareSerial bluetoothHC05(10, 11); // RX (Pin 10), TX (Pin 11)

unsigned long ultimoEnvio = 0;
const unsigned long INTERVALO_ENVIO = 2000; // Enviar cada 2 segundos

void setup() {
  Serial.begin(9600);
  bluetoothHC05.begin(9600);
  
  Serial.println("LactoSense: Sistema iniciado.");
  Serial.println("Esperando conexión Bluetooth con la App...");
}

void loop() {
  // 1. ESCUCHAR A LA APLICACIÓN ANDROID (Vía Bluetooth)
  if (bluetoothHC05.available()) {
    char comandoApp = bluetoothHC05.read();
    Serial.print("Comando recibido desde App: ");
    Serial.println(comandoApp);
    
    // Si la app envía 'R' o 'r', enviamos un archivo de reporte por lotes
    if (comandoApp == 'R' || comandoApp == 'r') {
      enviarReporteLote("101");
    }
  }

  // 2. ESCUCHAR AL MONITOR SERIE (Pruebas desde la PC)
  if (Serial.available()) {
    char comandoPc = Serial.read();
    bluetoothHC05.write(comandoPc);
    
    if (comandoPc == 'R' || comandoPc == 'r') {
      enviarReporteLote("101_PC");
    }
  }

  // 3. ENVÍO DE TELEMETRÍA EN TIEMPO REAL A LA APP
  if (millis() - ultimoEnvio > INTERVALO_ENVIO) {
    ultimoEnvio = millis();

    // 1) Temperatura de leche (rango típico: 4.5°C a 7.5°C o ambiente)
    float tempLeche = 6.2 + (random(-10, 10) / 10.0);
    // 2) pH de leche (rango óptimo: 6.60 a 6.80)
    float phLeche = 6.68 + (random(-5, 5) / 100.0);

    // Formato exacto para la App: "TEMP:6.2,PH:6.68"
    bluetoothHC05.print("TEMP:");
    bluetoothHC05.print(tempLeche, 1);
    bluetoothHC05.print(",PH:");
    bluetoothHC05.println(phLeche, 2);

    // Monitor Serie para depuración en PC
    Serial.print("Telemetría -> Temp: ");
    Serial.print(tempLeche, 1);
    Serial.print(" °C | pH: ");
    Serial.println(phLeche, 2);
  }
}

// ----------------------------------------------------------------------------
// Envío de archivo CSV automático a la App
// ----------------------------------------------------------------------------
void enviarReporteLote(String loteId) {
  Serial.println("\n[!] Enviando reporte CSV a la App...");
  
  bluetoothHC05.print("<<<START_FILE:lote_leche_");
  bluetoothHC05.print(loteId);
  bluetoothHC05.println(".csv>>>");
  
  bluetoothHC05.println("Hora,Temperatura_C,pH,Estado");
  bluetoothHC05.println("08:00,6.5,6.67,Aceptable");
  bluetoothHC05.println("08:15,6.2,6.68,Optimo");
  bluetoothHC05.println("08:30,6.1,6.66,Optimo");
  
  bluetoothHC05.println("<<<END_FILE>>>");
  
  Serial.println("[!] Reporte enviado con éxito.\n");
}
