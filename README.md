# SAR-Response

**App Android de estación de tierra para drones de búsqueda y rescate (SAR) con IA a bordo.**

SAR-Response es la parte "en manos del rescatista" del proyecto *Universal Edge-AI Rescue
Payload for Low-Cost Search and Rescue Drones* (IEEE Response Quest 2026). El payload es un
módulo de bajo costo que se monta en cualquier dron: una CanMV K230 busca personas en la
imagen de la cámara con una red neuronal (YOLO) **dentro del dron, sin internet**, y cuando
encuentra a alguien envía un aviso por **LoRa** con su posición GPS. Al mismo tiempo, transmite
video analógico por **5.8 GHz**.

La app junta todo eso en una sola pantalla para que el operador pueda **ver dónde está el
dron, dónde se detectó a alguien, mirar el video y decidir** si la detección es real.

<p align="center">
  <img src="docs/img/mapa.png" width="300" alt="Vista de mapa con recorrido del dron y detecciones">
  &nbsp;&nbsp;
  <img src="docs/img/video.png" width="300" alt="Vista de video con el mapa en miniatura">
</p>

<p align="center"><sub>Capturas con la misión simulada (modo demo). Datos del mapa © colaboradores de OpenStreetMap.</sub></p>

---

## Índice

- [Por qué existe](#por-qué-existe)
- [Qué hace](#qué-hace)
- [Cómo se usa en campo](#cómo-se-usa-en-campo)
- [La pantalla, parte por parte](#la-pantalla-parte-por-parte)
- [Arquitectura del sistema](#arquitectura-del-sistema)
- [Protocolo de los paquetes LoRa](#protocolo-de-los-paquetes-lora)
- [Estructura del código](#estructura-del-código)
- [Compilar e instalar](#compilar-e-instalar)
- [Estación tierra (ESP32)](#estación-tierra-esp32)
- [Mapas sin internet](#mapas-sin-internet)
- [Modo demo](#modo-demo)
- [Permisos y privacidad](#permisos-y-privacidad)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Limitaciones conocidas](#limitaciones-conocidas)
- [Hoja de ruta](#hoja-de-ruta)
- [Contribuir](#contribuir)
- [Licencia](#licencia)

---

## Por qué existe

En una búsqueda real, el operador del dron no puede quedarse mirando el video sin parpadear
durante horas, y una persona en el suelo vista desde 40–60 m de altura ocupa apenas unos
pocos píxeles. La IA del payload no se cansa, pero tampoco es infalible. SAR-Response está
pensada para ese punto medio:

- **La IA propone, la persona decide.** Cada detección llega como *pendiente* y el operador
  la confirma o la descarta mirando el video y el contexto.
- **Funciona sin cobertura.** Zonas de desastre, montaña o selva rara vez tienen datos
  móviles. El enlace es LoRa + Bluetooth y los mapas pueden ir precargados en el teléfono.
- **Hardware barato y común.** Un teléfono Android, un ESP32, un módulo LoRa E32 y un
  receptor de video FPV de los que se usan en drones de carreras.

## Qué hace

| Función | Detalle |
|---|---|
| **Mapa en vivo** | Recorrido del dron (línea azul), posición actual (punto azul), un pin por cada detección y la posición del operador. |
| **Video 5.8 GHz** | Video del VTX del payload con los recuadros de detección dibujados a bordo. *Integración UVC en desarrollo.* |
| **Vistas intercambiables** | Video o mapa a pantalla completa, con la otra vista en miniatura (picture-in-picture). Tocar la miniatura las intercambia sin perder el zoom ni la posición del mapa. |
| **Detecciones** | Lista con número, confianza de la IA, hora UTC y coordenadas. Botones **Confirmar** / **Descartar**. Las pendientes van primero. |
| **Avisos** | Cada detección nueva suena y vibra, aunque el operador esté mirando el video. |
| **Salud del enlace** | Estado del Bluetooth con la estación tierra, tiempo desde el último paquete LoRa, fix y satélites del GPS del payload, y paquetes recibidos / perdidos / corruptos. |
| **Reconexión automática** | Si se cae el Bluetooth, la app reintenta sola (1 s, 2 s, 4 s… hasta 10 s). |
| **Exportar GPX** | Detecciones confirmadas y pendientes (las descartadas no) como waypoints, más el recorrido del dron. Se abre en OsmAnd, Google Earth, QGIS, Garmin, etc. |
| **Mapas offline** | Si hay un archivo `.mbtiles` en el teléfono, la app no usa internet para el mapa. |
| **Modo demo** | Misión simulada para probar y presentar la app sin dron ni radio. |
| **Pantalla siempre encendida** | Mientras la app está abierta, el teléfono no se bloquea. |

## Cómo se usa en campo

1. **Antes de salir**
   - Si no habrá internet, copia el mapa del área (`.mbtiles`) al teléfono ([ver abajo](#mapas-sin-internet)).
   - Empareja una sola vez el teléfono con la estación tierra (**SAR-Estacion**) en
     *Ajustes → Bluetooth*.
2. **En el punto de despegue**
   - Enciende la estación tierra y el payload.
   - Abre SAR-Response, toca el indicador **Sin fuente** (arriba a la izquierda) y elige
     **SAR-Estacion**. Concede los permisos que pida.
   - Espera a ver *LoRa: hace N s* en verde y *GPS: fix* antes de despegar.
   - Si usas video, conecta el receptor 5.8 GHz al teléfono por OTG.
3. **Durante el vuelo**
   - Cuando suene una alerta, toca la detección en la lista: el mapa se centra en ella.
   - Mira el video y decide: **Confirmar** (pin rojo) o **Descartar** (pin gris).
4. **Al terminar o al pasar coordenadas a un equipo de tierra**
   - Toca **Exportar GPX** y compártelo, o dicta las coordenadas que aparecen en cada detección.

## La pantalla, parte por parte

### Barra de estado (arriba)

| Indicador | Verde | Ámbar | Rojo | Gris |
|---|---|---|---|---|
| **Fuente** (tocable) | Conectado a la estación tierra o a la demo | Conectando… | Reintentando tras una caída | Sin fuente elegida |
| **LoRa: hace N s** | Último paquete hace < 35 s | 35–65 s (se perdió un latido) | > 65 s: enlace con el dron probablemente caído | Aún no llega nada |
| **GPS** | El payload tiene fix | El payload no tiene fix (las alertas llegarán sin posición) | — | Sin latidos todavía |

Los umbrales de LoRa salen del latido del payload, que llega cada 30 s.

Debajo: **paquetes recibidos · perdidos · corruptos**.
- *Perdidos* se calcula con el número de secuencia: si después del 41 llega el 44, se
  perdieron 2.
- *Corruptos* son líneas con checksum inválido o mal formadas (ruido de radio).

Tener dos indicadores separados (Bluetooth y LoRa) permite saber **dónde** está el problema:
entre el teléfono y la estación, o entre la estación y el dron.

### Vista principal y miniatura

- **Mapa**:
  - Línea azul: recorrido del dron.
  - Punto azul grande: última posición conocida.
  - Pines: 🟠 pendiente · 🔴 confirmada · ⚪ descartada. El pin seleccionado se ve más grande.
  - Tocar un pin lo selecciona en la lista.
- **Video**: imagen del VTX del payload (pendiente de integrar, ver [hoja de ruta](#hoja-de-ruta)).
- **Miniatura** (arriba a la derecha): tócala para intercambiar mapa y video.

### Panel de detecciones (abajo)

- El encabezado muestra el total y cuántas están **por revisar**. Tócalo para plegar o desplegar.
- Cada fila muestra `#número · confianza · hora UTC` y las coordenadas.
- Tocar una fila centra el mapa en esa detección. Si estabas viendo el video, cambia al mapa.
- Las pendientes siempre van arriba y la lista sube sola cuando llega una nueva.

## Arquitectura del sistema

```
                 ┌─────────────── DRON (payload) ───────────────┐
                 │  Cámara → CanMV K230 (YOLO INT8, ~12 FPS)      │
                 │     │  GPS ──┘                                 │
                 │     ├─ UART → E32 LoRa 915 MHz ───────────────────────┐
                 │     └─ UART → ESP32 (dibuja recuadros) → VTX 5.8 GHz ─┼──┐
                 └────────────────────────────────────────────────┘     │  │
                                                                         │  │
                 ┌──────────── ESTACIÓN TIERRA ────────────┐            │  │
                 │  E32 LoRa ◄─────────────────────────────────────────┘  │
                 │     ├─► ESP32 ── Bluetooth SPP ──────────────┐         │
                 │     └─► YP-05 (USB) → PC (opcional)          │         │
                 └──────────────────────────────────────────────┘         │
                                                               ▼          │
                 ┌──────────── TELÉFONO ANDROID ───────────────────┐      │
                 │  SAR-Response  ◄── OTG ── receptor UVC 5.8 GHz ◄──────┘
                 └─────────────────────────────────────────────────┘
```

- **Datos (LoRa → Bluetooth):** pocos bytes, gran alcance y bajo consumo. Llevan lo que la
  app necesita para decidir: qué, dónde y cuándo.
- **Video (5.8 GHz analógico):** latencia muy baja y sin pantallas congeladas por pérdida de
  paquetes, como pasa con el video digital. Sirve para que una persona verifique lo que
  detectó la IA.

## Protocolo de los paquetes LoRa

Texto ASCII, una línea por paquete, terminada en `\r\n`, con checksum XOR estilo NMEA. Cada
paquete cabe en un solo envío de la E32 (< 58 bytes).

```
$SAR,<unidad>,<seq>,<lat>,<lon>,<confianza>,<hhmmss>*CS    persona detectada
$SAH,<unidad>,<seq>,<lat>,<lon>,<satélites>,<hhmmss>*CS    latido (cada 30 s)
```

| Campo | Descripción |
|---|---|
| `unidad` | Identificador del payload (p. ej. `A1`). Permite varios drones en el mismo canal. |
| `seq` | Contador 0–65535 compartido por ambos tipos; vuelve a 0 después de 65535. |
| `lat`, `lon` | Grados decimales (6 decimales ≈ 10 cm). **Vacíos** si el GPS no tiene fix. |
| `confianza` | 0.00–1.00, solo en `$SAR`. |
| `satélites` | Satélites en uso, solo en `$SAH`. |
| `hhmmss` | Hora UTC del GPS. |
| `CS` | XOR de todos los caracteres entre `$` y `*`, en dos dígitos hexadecimales. |

Ejemplos:

```
$SAH,A1,0,,,3,120000*2A
$SAR,A1,2,-12.046410,-77.042810,0.87,120041*1E
```

Cómo trata la app los paquetes:
- **Checksum inválido o formato raro:** se descarta y suma a *corruptos*.
- **Repetido** (mismo `seq` que el anterior): se ignora.
- **Salto de secuencia:** cuenta como paquetes perdidos. Un salto mayor a 1000 se interpreta
  como un reinicio del payload, no como miles de pérdidas.
- **Coordenadas fuera de rango:** el paquete se rechaza.

## Estructura del código

```
SAR-Response/
├── core/                      Kotlin puro (sin Android), con pruebas
│   └── src/main/kotlin/.../core/
│       ├── Packet.kt          Modelo de paquetes + PacketCodec (parse/format/checksum)
│       ├── MissionTracker.kt  Estado de la misión: detecciones, recorrido, estadísticas
│       ├── LinkSource.kt      Interfaz de cualquier origen de datos (Flow<String>)
│       ├── ReplaySource.kt    Misión simulada para demos
│       ├── GpxExporter.kt     Exportación GPX 1.1
│       └── Geo.kt             Distancia, rumbo y desplazamientos geográficos
├── app/                       Aplicación Android (Jetpack Compose)
│   └── src/main/java/.../sarresponse/
│       ├── MainActivity.kt    Permisos, elección de fuente, avisos, exportación
│       ├── MissionViewModel.kt Conexión con reintentos y estado expuesto a la UI
│       ├── link/BluetoothSppSource.kt  Bluetooth clásico (RFCOMM/SPP)
│       └── ui/
│           ├── Dashboard.kt   Barra de estado, intercambio video/mapa, panel de detecciones
│           ├── MapPane.kt     Mapa osmdroid (online u offline con MBTiles)
│           └── VideoPane.kt   Vista de video (marcador hasta integrar UVC)
├── firmware/esp32-bt-bridge/  Sketch Arduino del puente LoRa → Bluetooth
└── docs/img/                  Capturas para este README
```

**¿Por qué `core/` está separado?** Toda la lógica que no depende del teléfono (interpretar
paquetes, contar pérdidas, decidir qué exportar) vive en un módulo Kotlin puro. Así:
- se prueba en segundos sin emulador (`./gradlew :core:test`);
- se puede reutilizar en una futura app de escritorio con Kotlin Multiplatform, que leería la
  estación tierra por USB (YP-05) en lugar de Bluetooth.

Para agregar un nuevo origen de datos, basta con implementar `LinkSource`:

```kotlin
interface LinkSource {
    val label: String
    fun lines(): Flow<String>   // una línea por paquete; termina o falla si se cae el enlace
}
```

**Tecnologías:** Kotlin 2.4 · Jetpack Compose (Material 3) · Coroutines/StateFlow ·
[osmdroid](https://github.com/osmdroid/osmdroid) · Android Gradle Plugin 9 · minSdk 26 (Android 8.0).

## Compilar e instalar

Requisitos: Android Studio reciente (con AGP 9) y JDK 17 o superior.

```bash
git clone https://github.com/DaiYukichi/SAR-Response.git
cd SAR-Response
./gradlew :core:test           # pruebas del núcleo
./gradlew :app:assembleDebug   # APK en app/build/outputs/apk/debug/
./gradlew :app:installDebug    # instala en el teléfono conectado por USB
```

O simplemente abre la carpeta en Android Studio y presiona *Run*.

## Estación tierra (ESP32)

El sketch [`firmware/esp32-bt-bridge`](firmware/esp32-bt-bridge/esp32-bt-bridge.ino) convierte
un ESP32 en un puente: todo lo que recibe la E32 lo reenvía por Bluetooth al teléfono, y
también por USB para depurar.

| E32 | ESP32 |
|---|---|
| TXD | GPIO16 (RX2), configurable en el sketch |
| RXD | sin conectar |
| M0, M1 | GND (modo normal) |
| GND | GND |
| VCC | 5 V |

- Se necesita un ESP32 con **Bluetooth clásico** (ESP32-WROOM/WROVER). Los S2, S3 y C3 solo
  tienen BLE y no sirven para este sketch.
- Probado con el core ESP32 de Arduino 2.0.x.
- La E32 de tierra debe estar en el **mismo canal y velocidad de aire** que la del payload
  (en este proyecto: 915 MHz, 2.4 kbps, 9600 8N1).
- Un adaptador USB-serie (p. ej. YP-05) puede escuchar en paralelo la misma línea TXD para
  registrar en una PC. Solo un dispositivo debe manejar la línea RXD de la E32.

## Mapas sin internet

Sin configuración extra, la app descarga teselas de OpenStreetMap y las guarda en caché.
**No confíes en la caché para una operación real**: precarga el área.

1. Genera un archivo `.mbtiles` del área de búsqueda, por ejemplo con
   [Mobile Atlas Creator](https://mobac.sourceforge.io/) (formato *MBTiles SQLite*) o con QGIS
   (*Generar teselas XYZ (MBTiles)*). Zoom 12–18 suele bastar.
2. Copia el archivo al teléfono en:
   `Android/data/io.github.daiyukichi.sarresponse/files/mapas/`
3. Abre la app: si encuentra un `.mbtiles` ahí, lo usa y desactiva la descarga por red.

Los archivos `.mbtiles` no se suben a este repositorio porque pesan mucho. Si los generas a
partir de OpenStreetMap, respeta su licencia
([ODbL](https://www.openstreetmap.org/copyright)) y su
[política de uso de teselas](https://operations.osmfoundation.org/policies/tiles/), que no
permite descargas masivas desde sus servidores.

## Modo demo

En el selector de fuente, **Demo (misión simulada)** reproduce una misión de unos 80 segundos:

- el dron barre el área en pasadas paralelas (patrón de "cortadora de césped"),
- llegan latidos con posición y 4 detecciones con distintas confianzas,
- se simula la pérdida de un paquete, que aparece en el contador de *perdidos*.

Sirve para entrenar a operadores, probar la interfaz y presentar el proyecto sin hardware.
Las coordenadas de la demo son un punto genérico en David, Chiriquí (Panamá), y se pueden
cambiar en `ReplaySource.kt`.

## Permisos y privacidad

| Permiso | Para qué |
|---|---|
| Bluetooth (conectar) | Hablar con la estación tierra. |
| Ubicación | Mostrar la posición del operador en el mapa. Es opcional: si se niega, todo lo demás funciona. |
| Internet | Solo para descargar teselas del mapa cuando no hay `.mbtiles`. |
| Vibración | Avisar de nuevas detecciones. |

- **Todo se queda en el teléfono.** La app no tiene servidor, cuentas, analíticas ni
  publicidad. Los datos solo salen si el operador exporta un GPX y lo comparte.
- **El payload no transmite imágenes de personas por LoRa**, solo coordenadas, confianza y hora.
- **Decisión humana obligatoria.** La app nunca trata una detección como confirmada por sí
  sola. Cada confirmación o descarte guarda su hora para poder revisar la operación después.

## Decisiones de diseño

- **Bluetooth clásico (SPP) en vez de BLE:** es el camino más simple y robusto para un flujo
  de texto línea a línea desde un ESP32. BLE queda como opción si en el futuro hay app de iOS.
- **Video analógico en vez de digital:** latencia de pocos milisegundos y degradación
  gradual (con nieve en la imagen) en vez de congelarse.
- **Texto legible en vez de binario:** los paquetes se pueden leer con cualquier terminal
  serie, lo que facilita depurar en campo. Caben igual en un envío de la E32.
- **Interfaz oscura y de alto contraste:** se lee mejor al sol y gasta menos batería en
  pantallas OLED.
- **Mapa y video siempre montados:** al intercambiarlos solo cambia su tamaño, así el mapa no
  se recarga ni pierde el zoom.

## Limitaciones conocidas

- **El video UVC aún no está integrado**: la vista de video es un marcador.
- **La misión vive en memoria**: sobrevive a girar la pantalla, pero si Android cierra la
  app se pierde. Exporta el GPX si necesitas conservarla.
- La posición de una detección es la del GPS del **dron** en ese momento, no la proyección
  exacta del píxel al suelo. A 40–60 m de altura el error puede ser de varios metros.
- La app asume una sola estación tierra a la vez.
- Interfaz solo en español por ahora.

## Hoja de ruta

- [ ] Video 5.8 GHz por receptor UVC (OTG).
- [ ] Registro persistente de la misión (Room) y recuperación tras cierre.
- [ ] Distancia y rumbo desde el operador hasta cada detección, y botón "navegar a".
- [ ] Notas por detección (p. ej. "persona herida", "requiere camilla").
- [ ] Proyección de la detección al suelo usando altura y orientación de la cámara.
- [ ] Soporte para varios drones (`unidad`) con colores distintos.
- [ ] Traducción al inglés.
- [ ] App de escritorio con Kotlin Multiplatform reutilizando `core/`.

## Contribuir

Se aceptan *issues* y *pull requests*. Si cambias algo en `core/`, agrega o actualiza las
pruebas y verifica que `./gradlew :core:test` pase. Si cambias el protocolo, actualiza
también el firmware del payload y la sección [Protocolo](#protocolo-de-los-paquetes-lora).

## Licencia

Código bajo licencia [Apache-2.0](LICENSE).

Datos de mapas © [colaboradores de OpenStreetMap](https://www.openstreetmap.org/copyright),
disponibles bajo la licencia ODbL.
