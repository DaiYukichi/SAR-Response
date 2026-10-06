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
