#!/usr/bin/env bash
# Genera el mapa offline que va DENTRO de la app: el mundo con poco detalle (para ubicarse en
# cualquier parte) + una zona con detalle de calle. Datos: OpenStreetMap vía Protomaps (ODbL).
#
# Uso:
#   tools/mapa/descargar_mapa.sh                              # Chiriquí (por defecto, ~34 MB con el mundo)
#   tools/mapa/descargar_mapa.sh "-82.52,8.36,-82.35,8.50"    # otra zona: oeste,sur,este,norte
#   tools/mapa/descargar_mapa.sh "-83.06,7.15,-77.15,9.70" 13 # zoom máximo menor = archivo más chico
#
# Usa el recortador de la propia app (core/map/MapExtractor): pide por HTTP solo los bytes de la
# zona elegida, no el planeta. No requiere herramientas externas, solo Java (viene con Android Studio).
set -euo pipefail

BBOX="${1:--83.06,7.95,-81.75,9.10}"
MAXZOOM="${2:-15}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/app/src/main/assets/mapa/region.pmtiles"

cd "$ROOT"
./gradlew -q :core:extraerMapa --args="$DEST $BBOX $MAXZOOM"
