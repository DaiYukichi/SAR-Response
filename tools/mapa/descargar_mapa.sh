#!/usr/bin/env bash
# Recorta el mapa base de Protomaps (OpenStreetMap, licencia ODbL) para una zona y lo deja
# dentro de la app, para que el mapa funcione SIN internet.
#
# Requiere la herramienta pmtiles: https://github.com/protomaps/go-pmtiles/releases
#
# Uso:
#   tools/mapa/descargar_mapa.sh                         # Chiriquí (por defecto, ~20 MB)
#   tools/mapa/descargar_mapa.sh "-82.52,8.36,-82.35,8.50"  # otra zona: oeste,sur,este,norte
#
# Usa el recorte por rango de bytes de pmtiles: solo baja la zona pedida, no el planeta.
# (Descargar teselas en masa de los servidores de OpenStreetMap NO está permitido.)
set -euo pipefail

BBOX="${1:--83.06,7.95,-81.75,9.10}"
MAXZOOM="${MAXZOOM:-15}"
DEST="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/assets/mapa/region.pmtiles"

command -v pmtiles >/dev/null || { echo "Falta la herramienta pmtiles (ver encabezado)."; exit 1; }

# Build diaria más reciente disponible (se guardan las de la última semana).
for d in 0 1 2 3 4 5 6; do
  FECHA=$(date -v-"${d}"d +%Y%m%d 2>/dev/null || date -d "-${d} day" +%Y%m%d)
  if curl -sf -r 0-0 -o /dev/null "https://build.protomaps.com/${FECHA}.pmtiles"; then break; fi
done
echo "Mapa base: ${FECHA} · zona ${BBOX} · zoom máx. ${MAXZOOM}"
pmtiles extract "https://build.protomaps.com/${FECHA}.pmtiles" "$DEST" --bbox="$BBOX" --maxzoom="$MAXZOOM"
echo "Listo: $DEST ($(du -h "$DEST" | cut -f1))"
