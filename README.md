# SAR-Response

**App Android de estación de tierra para drones de búsqueda y rescate (SAR) con IA a bordo.**

SAR-Response es la parte "en manos del rescatista" del proyecto *Universal Edge-AI Rescue
Payload for Low-Cost Search and Rescue Drones* (IEEE Response Quest 2026). El payload es un
módulo de bajo costo que se monta en cualquier dron: una CanMV K230 busca personas en la
imagen de la cámara con una red neuronal (YOLO) **dentro del dron, sin internet**, y cuando
encuentra a alguien envía un aviso por **LoRa** con su posición GPS. Al mismo tiempo, transmite
video analógico por **5.8 GHz**.

La app junta todo eso en una sola pantalla para que el operador pueda **ver dónde está el
dron, dónde se detectó a alguien, mirar el video, decidir** si la detección es real y
**guiar a quien va a pie** hasta esa persona.

<p align="center">
  <img src="docs/img/mapa.png" width="300" alt="Mapa con recorrido del dron, detecciones y navegación hacia la detección #3">
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
- [Trabajo futuro](#trabajo-futuro)
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
- **Funciona 100 % sin internet.** Zonas de desastre, montaña o selva rara vez tienen datos
  móviles. El enlace es LoRa + Bluetooth y el mapa va dentro de la app. **La app ni siquiera
  tiene permiso de internet**: es imposible que dependa de la red.
- **Hardware barato y común.** Un teléfono Android, un ESP32, un módulo LoRa E32 y un
  receptor de video FPV de los que se usan en drones de carreras.

## Qué hace

| Función | Detalle |
|---|---|
| **Mapa en vivo** | Recorrido del dron (línea azul), posición actual (punto azul), un pin por cada detección, la posición del operador (celeste) y una leyenda. Las detecciones pendientes "laten" para llamar la atención. |
| **Video 5.8 GHz** | Video del VTX del payload con los recuadros de detección dibujados a bordo. *Integración UVC en desarrollo.* |
| **Vistas intercambiables** | Video o mapa a pantalla completa, con la otra vista en miniatura (picture-in-picture). Tocar la miniatura las intercambia sin perder el zoom ni la posición del mapa. |
| **Detecciones** | Tarjetas con número, confianza de la IA (con color), hora UTC, coordenadas y **distancia y rumbo desde el operador** ("293 m · N 7°"). Botones **Confirmar** / **Descartar**. Las pendientes van primero. |
| **Navegar a una detección** | Banner con la distancia, el rumbo y una flecha que apunta hacia la persona **según hacia dónde mira el teléfono** (brújula), más una línea punteada en el mapa del operador al objetivo. |
| **Compartir coordenadas** | En una detección confirmada, envía por WhatsApp, SMS, correo, etc. un texto que se entiende sin la app: coordenadas, distancia y rumbo desde el operador, enlace a OpenStreetMap y enlace `geo:`. |
| **Avisos** | Cada detección nueva suena, vibra y muestra un mensaje con su confianza, distancia y dirección ("Detección #1 · 87% · 293 m N"), aunque el operador esté mirando el video. |
| **Protección contra toques equivocados** | La lista no se desplaza sola, y durante 0,8 s después de que se reordena (llegó una detección o se tomó una decisión) los botones no responden. Así un toque no cae en otra tarjeta. |
| **Salud del enlace** | Estado del Bluetooth con la estación tierra, tiempo desde el último paquete LoRa, fix y satélites del GPS del payload, y paquetes recibidos / perdidos / corruptos. |
| **Reconexión automática** | Si se cae el Bluetooth, la app reintenta sola (1 s, 2 s, 4 s… hasta 10 s). |
| **Exportar GPX** | Detecciones confirmadas y pendientes (las descartadas no) como waypoints, más el recorrido del dron. Se abre en OsmAnd, Google Earth, QGIS, Garmin, etc. |
| **Mapa offline** | Mapa vectorial de OpenStreetMap (Chiriquí incluido, ~20 MB) con calles, lugares y nombres en español. Se puede importar el de otra zona. Nada se descarga en campo. |
| **Arranque listo** | Al abrir, pide de una vez los permisos de ubicación y Bluetooth, y el mapa arranca centrado en la posición del operador. |
| **Modo demo** | Misión simulada para probar y presentar la app sin dron ni radio. |
| **Pantalla siempre encendida** | Mientras la app está abierta, el teléfono no se bloquea. |

## Cómo se usa en campo

1. **Antes de salir**
   - Si la búsqueda es fuera de Chiriquí, importa el mapa de esa zona ([ver abajo](#mapas-sin-internet)).
   - Abre la app con cielo despejado unos minutos antes: sin internet, el primer fix del GPS
     del teléfono puede tardar de 30 s a unos minutos.
   - Empareja una sola vez el teléfono con la estación tierra (**SAR-Estacion**) en
     *Ajustes → Bluetooth*.
2. **En el punto de despegue**
   - Enciende la estación tierra y el payload.
   - Abre SAR-Response, toca **Elegir fuente** (arriba a la derecha) y elige
     **SAR-Estacion**. Concede los permisos de Bluetooth y ubicación.
   - Espera a ver *Enlace LoRa* en verde y *GPS payload: fix* antes de despegar.
   - Si usas video, conecta el receptor 5.8 GHz al teléfono por OTG.
3. **Durante el vuelo**
   - Cuando suene una alerta, toca la detección en la lista: el mapa se centra en ella.
   - Mira el video y decide: **Confirmar** (pin rojo) o **Descartar** (pin gris).
4. **Para llegar a la persona**
   - Toca **➤ Navegar a** en la detección: la flecha del banner apunta hacia ella y la
     distancia se actualiza mientras caminas.
   - O toca **Compartir coordenadas** para mandárselas al equipo que va a pie.
5. **Al terminar**
   - Toca **Exportar GPX** para guardar las detecciones y el recorrido.

## La pantalla, parte por parte

### Encabezado (arriba)

| Indicador | Verde | Ámbar | Rojo | Gris |
|---|---|---|---|---|
| **Fuente** (botón arriba a la derecha) | Conectado a la estación tierra o a la demo | Conectando… | Reintentando tras una caída | Ninguna fuente elegida |
| **Enlace LoRa: hace N s** | Último paquete hace < 35 s | 35–65 s (se perdió un latido) | > 65 s: enlace con el dron probablemente caído | Aún no llega nada |
| **GPS payload** | El payload tiene fix | El payload no tiene fix (las alertas llegarán sin posición) | — | Sin latidos todavía |

Los umbrales de LoRa salen del latido del payload, que llega cada 30 s.

Debajo: **paquetes recibidos · perdidos · corruptos**. Arriba a la derecha está **Exportar GPX**.
- *Perdidos* se calcula con el número de secuencia: si después del 41 llega el 44, se
  perdieron 2.
- *Corruptos* son líneas con checksum inválido o mal formadas (ruido de radio).

Tener dos indicadores separados (Bluetooth y LoRa) permite saber **dónde** está el problema:
entre el teléfono y la estación, o entre la estación y el dron.

### Vista principal y miniatura

- **Mapa**:
  - Línea azul: recorrido del dron.
  - Punto azul grande: última posición conocida.
  - Punto celeste: el operador (el teléfono).
  - Pines: 🟠 pendiente (con un halo que late) · 🔴 confirmada · ⚪ descartada. El seleccionado
    lleva un anillo azul.
  - Línea roja punteada: del operador a la detección hacia la que se está navegando.
  - Tocar un pin lo selecciona en la lista.
- **Banner de navegación** (abajo del mapa): distancia, punto cardinal, grados y una flecha.
  - Si el teléfono tiene brújula, la flecha apunta hacia la persona **según hacia dónde mira el
    teléfono**: basta con girar hasta que apunte hacia adelante y caminar.
  - Sin brújula, el rumbo se da respecto al norte, y el banner lo indica.
- **Video**: imagen del VTX del payload (pendiente de integrar, ver [trabajo futuro](#trabajo-futuro)).
- **Miniatura** (arriba a la derecha): tócala para intercambiar mapa y video.

### Panel de detecciones (abajo)

- El encabezado muestra el total y cuántas están **por revisar**. Tócalo para plegar o desplegar.
- Cada tarjeta muestra el número, la confianza de la IA y su estado; debajo, la distancia y el
  rumbo desde el operador, la hora UTC y las coordenadas.
- **Confianza con color:** ≥ 80 % rosado (alta), 60–79 % ámbar (media), < 60 % gris (baja; mírala con
  más cuidado en el video).
- Botones según el estado:
  - Pendiente: **✓ Confirmar**, **✕ Descartar** y **➤** (navegar).
  - Confirmada: **➤ Navegar a** y **Compartir coordenadas**.
- Tocar una tarjeta centra el mapa en esa detección. Si estabas viendo el video, cambia al mapa.
- Las pendientes siempre van arriba. Después de cada decisión la lista vuelve arriba, donde quedan
  las que faltan revisar. Si hay tarjetas fuera de la vista, aparece **↑ Ver más**.

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
│       └── Geo.kt             Distancia, rumbo, punto cardinal y desplazamientos
├── app/                       Aplicación Android (Jetpack Compose)
│   └── src/main/java/.../sarresponse/
│       ├── MainActivity.kt    Permisos, elección de fuente, avisos, exportación
│       ├── MissionViewModel.kt Conexión con reintentos y estado expuesto a la UI
│       ├── link/BluetoothSppSource.kt  Bluetooth clásico (RFCOMM/SPP)
│       └── ui/
│           ├── Dashboard.kt   Barra de estado, intercambio video/mapa, panel de detecciones
│           ├── MapPane.kt     Mapa MapLibre: capas de recorrido, pines, operador y navegación
│           ├── OfflineMap.kt  Archivo .pmtiles local, importación y estilo offline
│           ├── Sensors.kt     Ubicación del operador y brújula del teléfono
│           └── VideoPane.kt   Vista de video (marcador hasta integrar UVC)
├── app/src/main/assets/mapa/  Estilo, letras e íconos del mapa (y region.pmtiles, generado)
├── tools/mapa/                Scripts para generar el mapa offline y su estilo
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
[MapLibre Native](https://maplibre.org) + [PMTiles](https://protomaps.com) · Android Gradle Plugin 9 · minSdk 26 (Android 8.0).

## Compilar e instalar

Requisitos: Android Studio reciente (con AGP 9) y JDK 17 o superior. El mapa no se sube al
repositorio por su tamaño: genéralo una vez con `tools/mapa/descargar_mapa.sh` (ver
[Mapas sin internet](#mapas-sin-internet)). Si no lo generas, la app compila igual y funciona
sin mapa base.

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

El mapa es **vectorial y local**: un archivo `.pmtiles` con datos de OpenStreetMap (vía
[Protomaps](https://protomaps.com)), dibujado con [MapLibre](https://maplibre.org). Las letras y
los íconos del mapa también van dentro de la app, así que **nada se descarga nunca**.

- **Incluido:** Chiriquí completo (~20 MB, detalle hasta nivel de calle). Al primer arranque se
  copia al almacenamiento interno; por eso la primera vez el mapa puede tardar unos segundos.
- **Otra zona:** en **Elegir fuente → Importar mapa (.pmtiles)…** eliges un archivo que ya esté
  en el teléfono (Descargas, memoria USB, etc.). **Volver al mapa incluido** lo deshace.
- **Sin ningún mapa:** la app sigue funcionando; recorrido, pines, distancias y navegación se
  dibujan sobre un fondo liso.

### Generar el mapa de una zona (en la computadora, con internet)

Necesitas la herramienta [`pmtiles`](https://github.com/protomaps/go-pmtiles/releases).

```bash
tools/mapa/descargar_mapa.sh                              # Chiriquí (por defecto)
tools/mapa/descargar_mapa.sh "-82.52,8.36,-82.35,8.50"    # otra zona: oeste,sur,este,norte
```

El script recorta solo la zona pedida del mapa mundial diario de Protomaps y la deja en
`app/src/main/assets/mapa/region.pmtiles`, que se empaqueta al compilar. Para usarlo como mapa
importado en vez de compilarlo, copia ese archivo al teléfono e impórtalo desde la app.

> No se usan los servidores de teselas de OpenStreetMap: su
> [política](https://operations.osmfoundation.org/policies/tiles/) prohíbe las descargas masivas.

El estilo del mapa (tema oscuro, etiquetas en español) se genera con
`cd tools/mapa && npm install && npm run estilo`.

## Modo demo

En el selector de fuente, **Demo (misión simulada)** reproduce una misión de unos 80 segundos:

- el dron barre el área en pasadas paralelas (patrón de "cortadora de césped"),
- llegan latidos con posición y 4 detecciones con distintas confianzas,
- se simula la pérdida de un paquete, que aparece en el contador de *perdidos*,
- el operador queda en un punto de despegue simulado al suroeste del área, para que la
  distancia, el rumbo y "Navegar a" funcionen sin GPS. La brújula sí es la real del teléfono.

Sirve para entrenar a operadores, probar la interfaz y presentar el proyecto sin hardware.
Las coordenadas de la demo son un punto genérico en David, Chiriquí (Panamá), y se pueden
cambiar en `ReplaySource.kt`.

## Permisos y privacidad

| Permiso | Para qué |
|---|---|
| Bluetooth (conectar) | Hablar con la estación tierra. |
| Ubicación | Posición del operador: punto en el mapa, distancia y rumbo a cada detección y "Navegar a". Si se niega, todo lo demás funciona. |
| Vibración | Avisar de nuevas detecciones. |

- **Sin internet, por diseño.** El manifiesto elimina los permisos de red (incluidos los que
  agrega la librería del mapa), así que Android no deja que la app use la red.
- **Todo se queda en el teléfono.** La app no tiene servidor, cuentas, analíticas ni
  publicidad. Los datos solo salen si el operador exporta un GPX o comparte una detección.
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
- **La lista nunca se mueve bajo el dedo:** confirmar o descartar la tarjeta equivocada en una
  búsqueda real es grave, así que se evita el desplazamiento automático y se bloquean los botones
  un instante tras cada reordenamiento.
- **Compartir como texto plano:** lo entiende cualquier persona en cualquier app, incluso sin
  SAR-Response instalada.
- **Mapa vectorial en vez de imágenes:** Chiriquí entero pesa ~20 MB, se ve nítido a cualquier
  zoom y los nombres se pueden mostrar en español.
- **Mapa y video siempre montados:** al intercambiarlos solo cambia su tamaño, así el mapa no
  se recarga ni pierde el zoom.

## Limitaciones conocidas

- **El video UVC aún no está integrado**: la vista de video es un marcador.
- **La misión vive en memoria**: sobrevive a girar la pantalla, pero si Android cierra la
  app se pierde. Exporta el GPX si necesitas conservarla.
- La posición de una detección es la del GPS del **dron** en ese momento, no la proyección
  exacta del píxel al suelo. A 40–60 m de altura el error puede ser de varios metros.
- La brújula del teléfono indica el norte **magnético**; la diferencia con el norte geográfico
  es pequeña en Panamá (unos 2°), pero en otras regiones puede ser mayor. Además, las brújulas de
  los teléfonos se descalibran cerca de metales: si la flecha no tiene sentido, haz un "8" con el
  teléfono.
- El mapa incluido tiene detalle hasta nivel de calle (zoom 15); más cerca se amplía el mismo
  detalle. No incluye imágenes satelitales.
- El APK de depuración pesa ~80 MB porque incluye el mapa y el motor de mapas para todos los
  tipos de procesador; una versión de publicación por procesador pesa bastante menos.
- La distancia y el rumbo son en línea recta; no consideran el terreno ni los caminos.
- La app asume una sola estación tierra a la vez.
- Interfaz solo en español por ahora.

## Trabajo futuro

La app actual cubre el ciclo completo **detectar → verificar → decidir → guiar**. Lo que sigue
está diseñado (parte de ello en un mockup interactivo), pero **todavía no se puede implementar**
porque depende de hardware o radios que el prototipo no tiene. Se documenta aquí como la
dirección del proyecto.

### A corto plazo (solo software)

- [ ] **Video 5.8 GHz** dentro de la app con el receptor UVC por OTG.
- [ ] **Registro persistente de la misión** (Room) para que sobreviva si Android cierra la app.
- [ ] **Búsquedas separadas:** cada misión con su nombre, su recorrido y sus detecciones;
      "Nueva búsqueda" archiva la anterior y permite volver a revisarla.
- [ ] **Notas por detección** (p. ej. "persona herida", "requiere camilla").
- [ ] **Tema claro** como alternativa al oscuro.
- [ ] Varios drones a la vez (campo `unidad`) con colores distintos.
- [ ] Traducción al inglés.
- [ ] **App de escritorio** con Kotlin Multiplatform reutilizando `core/` y la estación tierra por USB.

### Red de rescate en campo (requiere nuevo hardware)

La idea es que el sistema no termine en el teléfono del operador, sino que llegue hasta quien
camina hacia la víctima.

- [ ] **SAR-Beacon, la vista del rescatista:** un nodo de mano (ESP32 + E32 + GPS + pantalla)
      que muestra una flecha, la distancia y el rumbo hacia la detección que el operador le
      asignó, con un botón **"Voy en camino"**.
- [ ] **Enviar al equipo de tierra por radio:** desde una detección confirmada, el operador
      asigna el objetivo a un rescatista concreto, sin depender de datos móviles.
- [ ] **Nodos multi-rol:** el mismo firmware cumple distintos papeles según su identificador:
      `A#` dron · `G#` tierra / rescatista · `R#` repetidor.
- [ ] **Emparejamiento por QR** entre la app y cada nodo.
- [ ] **Nuevos paquetes** en el mismo formato de texto con checksum:
      - `$SAG` *go-to*: el operador asigna un objetivo a un rescatista.
      - `$SGP` posición periódica del rescatista (aparece en el mapa del operador).
      - `$SGA` confirmación (*ack*) de que el rescatista recibió la orden.
- [ ] **Malla por inundación (*flooding*)** para cubrir quebradas y laderas sin línea de vista:
      cada paquete lleva origen, secuencia y TTL; los nodos descartan duplicados y los
      repetidores (`R#`) retransmiten.

### Mejoras de precisión

- [ ] Proyectar la detección al suelo usando la altura del dron y la orientación de la cámara,
      en vez de usar la posición del dron.
- [ ] Corregir la declinación magnética de la brújula.
- [ ] Rutas a pie sobre el terreno (curvas de nivel, senderos) en vez de línea recta.

## Contribuir

Se aceptan *issues* y *pull requests*. Si cambias algo en `core/`, agrega o actualiza las
pruebas y verifica que `./gradlew :core:test` pase. Si cambias el protocolo, actualiza
también el firmware del payload y la sección [Protocolo](#protocolo-de-los-paquetes-lora).

## Licencia

Código bajo licencia [Apache-2.0](LICENSE).

- Datos de mapas © [colaboradores de OpenStreetMap](https://www.openstreetmap.org/copyright),
  licencia ODbL, procesados por [Protomaps](https://protomaps.com).
- Letras Noto Sans: [SIL Open Font License](app/src/main/assets/mapa/licencias/fuentes-OFL.txt).
- Íconos del mapa: derivados de [tangrams/icons](https://github.com/tangrams/icons), licencia
  [MIT](app/src/main/assets/mapa/licencias/iconos-MIT.md).
