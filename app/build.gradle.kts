plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi)
}

/** "1.4.12" → 10412: sube siempre que sube la versión (cada parte, hasta 99). */
val appVersion = providers.gradleProperty("wrtc.version").get()
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "wrtc.version: cada parte, de 0 a 99" }
    major * 10000 + minor * 100 + patch
}

/**
 * Firma de la versión release: de variables de entorno (la CI, con los secrets) o de propiedades
 * de Gradle (~/.gradle/gradle.properties en el PC de quien publica). Sin ellas, la release sale
 * sin firmar. La clave nunca va en el repositorio.
 */
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name.lowercase().replace('_', '.'))).orNull

val releaseKeystore = signingValue("WRTC_KEYSTORE")

android {
    namespace = "dev.angel.wrtcontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.angel.wrtcontrol"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("WRTC_KEYSTORE_PASSWORD")
                keyAlias = signingValue("WRTC_KEY_ALIAS")
                keyPassword = signingValue("WRTC_KEY_PASSWORD")
            }
        }
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

    testOptions {
        // Las pruebas de capturas (Roborazzi) pintan con los recursos de la app: tema, textos, fuente.
        unitTests.isIncludeAndroidResources = true
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
    // Solo para dibujar el código QR con el que se añade otro teléfono (no lee códigos: eso lo hace la cámara).
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    // Acceso remoto: WireGuard en espacio de usuario (tunnel/, compilado con tools/build-tunnel.sh).
    implementation(files("libs/wrtcvpn.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Pruebas de capturas en la JVM: Robolectric pinta y Roborazzi compara con la imagen guardada.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    detektPlugins(libs.detekt.ktlint)
    // Lint corre con su propio classpath (`androidLintTool`), aparte del de los plugins.
    libs.bundles.build.seguras.get().forEach { constraints.add("androidLintTool", it) }
}
