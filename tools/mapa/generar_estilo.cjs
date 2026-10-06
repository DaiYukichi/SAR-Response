// Genera app/src/main/assets/mapa/estilo-oscuro.json a partir de @protomaps/basemaps.
// Uso:  cd tools/mapa && npm install && npm run estilo
// Letras y íconos se sirven desde los assets de la app (asset://), así el mapa no usa internet.
// {{PMTILES_URL}} lo reemplaza la app con la ruta local del archivo .pmtiles.
const { layers, namedFlavor } = require("@protomaps/basemaps");
const L = layers("protomaps", namedFlavor("dark"), { lang: "es" });
const style = {
  version: 8,
  name: "SAR-Response oscuro (Protomaps)",
  glyphs: "asset://mapa/fonts/{fontstack}/{range}.pbf",
  sprite: "asset://mapa/sprites/dark",
  sources: { protomaps: { type: "vector", url: "{{PMTILES_URL}}", attribution: "© OpenStreetMap contributors · Protomaps" } },
  layers: L,
};
const out = __dirname + "/../../app/src/main/assets/mapa/estilo-oscuro.json";
require("fs").writeFileSync(out, JSON.stringify(style));
const fonts = new Set();
JSON.stringify(L, (k, v) => { if (k === "text-font") JSON.stringify(v).match(/Noto[^"]*/g)?.forEach(f => fonts.add(f)); return v; });
console.log("layers", L.length, "fonts", [...fonts]);
