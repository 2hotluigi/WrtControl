// Versiones mínimas para librerías que los plugins traen al classpath del build con
// vulnerabilidades conocidas (ver build-seguras en libs.versions.toml). Una restricción solo
// sube: si un plugin pide una más nueva, gana la suya.
buildscript {
    dependencies {
        constraints {
            libs.bundles.build.seguras.get().forEach { classpath(it) }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 trae Kotlin integrado; declarar el plugin (sin aplicarlo) fija la versión de Kotlin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}
