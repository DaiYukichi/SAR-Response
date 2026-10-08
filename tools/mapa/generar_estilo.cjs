// Genera los estilos del mapa a partir de @protomaps/basemaps (tema oscuro):
//   app/src/main/assets/mapa/estilo-oscuro.json      etiquetas en español
//   app/src/main/assets/mapa/estilo-oscuro-en.json   etiquetas en inglés
// Uso:  cd tools/mapa && npm install && npm run estilo
// Letras y íconos se sirven desde los assets de la app (asset://), así el mapa no usa internet.
// {{PMTILES_URL}} lo reemplaza la app con la ruta del archivo .pmtiles (local o en línea).
const { layers, namedFlavor } = require("@protomaps/basemaps");
const fs = require("fs");

for (const [lang, file] of [["es", "estilo-oscuro.json"], ["en", "estilo-oscuro-en.json"]]) {
  const L = layers("protomaps", namedFlavor("dark"), { lang });
  const style = {
    version: 8,
    name: `SAR-Response oscuro (Protomaps, ${lang})`,
    glyphs: "asset://mapa/fonts/{fontstack}/{range}.pbf",
    sprite: "asset://mapa/sprites/dark",
    sources: { protomaps: { type: "vector", url: "{{PMTILES_URL}}", attribution: "© OpenStreetMap contributors · Protomaps" } },
    layers: L,
  };
  fs.writeFileSync(`${__dirname}/../../app/src/main/assets/mapa/${file}`, JSON.stringify(style));
  const fonts = new Set();
  JSON.stringify(L, (k, v) => { if (k === "text-font") JSON.stringify(v).match(/Noto[^"]*/g)?.forEach(f => fonts.add(f)); return v; });
  console.log(file, "capas", L.length, "letras", [...fonts]);
}
