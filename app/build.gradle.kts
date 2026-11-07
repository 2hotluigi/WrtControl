plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

android {
    namespace = "dev.angel.wrtcontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.angel.wrtcontrol"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Dirección sugerida en la pantalla de conexión: la VM de pruebas en VirtualBox.
            buildConfigField("String", "DEFAULT_ROUTER", "\"192.168.56.2\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_ROUTER", "\"192.168.1.1\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // La CI falla con cualquier aviso nuevo; los que ya había quedan en la línea base
        // (`gradlew updateLintBaseline` la regenera).
        warningsAsErrors = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
        // "Hay una versión más nueva": cambia sola con cada publicación y pondría la CI en rojo sin
        // tocar nada. Las actualizaciones se hacen aparte.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

// Análisis estático + estilo (ktlint dentro de detekt). Lo que ya había queda en la línea base:
// la CI solo falla por código nuevo. `gradlew detektBaseline` la regenera.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
}

// Cobertura de las pruebas unitarias: `gradlew koverHtmlReportDebug` / `koverXmlReportDebug`.
kover {
    reports {
        filters {
            excludes {
                // Compose y el código generado distorsionan la cifra sin decir nada útil.
                annotatedBy("androidx.compose.runtime.Composable")
                classes("*.BuildConfig", "*ComposableSingletons*", "dev.angel.wrtcontrol.go.*")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.biometric)
    // Acceso remoto: WireGuard en espacio de usuario (tunnel/, compilado con tools/build-tunnel.sh).
    implementation(files("libs/wrtcvpn.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    detektPlugins(libs.detekt.ktlint)
}
