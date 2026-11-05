plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 trae Kotlin integrado; declarar el plugin (sin aplicarlo) fija la versión de Kotlin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
