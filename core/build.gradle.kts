// Lógica pura (sin android.*): parser, estado de la misión, replay y GPX.
// Pensado para moverse a commonMain si algún día se hace la app desktop con KMP.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Genera el mapa offline que va dentro de la app (ver tools/mapa/descargar_mapa.sh).
tasks.register<JavaExec>("extraerMapa") {
    group = "mapa"
    description = "Recorta mundo general + detalle de una zona del mapa de Protomaps a un .pmtiles"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.daiyukichi.sarresponse.core.map.MapExtractorCliKt")
    workingDir = rootProject.projectDir
}
