// Puente LoRa -> Bluetooth de la estación tierra de SAR-Response.
//
// Lee lo que llega por la E32 (texto $SAR/$SAH, 9600 8N1) y lo reenvía tal cual
// por Bluetooth clásico SPP al teléfono. También lo copia al USB para depurar.
//
// Cableado:
//   E32 TXD -> ESP32 GPIO16 (RX2)      (el YP-05 puede escuchar la misma línea en paralelo)
//   E32 RXD -> sin conectar            (nunca la manejen el ESP32 y el YP-05 a la vez)
//   E32 M0, M1 -> GND (modo normal)    GND común
//
// Probado con el core ESP32 de Arduino 2.0.x (BluetoothSerial). Requiere un ESP32 con
// Bluetooth clásico (ESP32-WROOM/WROVER); los S2/S3/C3 no lo tienen.

#include <BluetoothSerial.h>

static const int E32_RX_PIN = 16;   // ajusta si tu placa usa otro pin
static const long E32_BAUD = 9600;
static const char *BT_NAME = "SAR-Estacion";

BluetoothSerial bt;

void setup() {
  Serial.begin(115200);
  Serial2.begin(E32_BAUD, SERIAL_8N1, E32_RX_PIN, -1);
  bt.begin(BT_NAME);
  Serial.printf("Puente listo. Empareja el telefono con \"%s\".\n", BT_NAME);
}

void loop() {
  while (Serial2.available()) {
    int c = Serial2.read();
    if (bt.hasClient()) bt.write(c);
    Serial.write(c);
  }
}
