import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.simultrans"
    // compileSdk/targetSdk 36: requisito de Google Play desde el 31 de
    // agosto de 2026 para poder publicar o actualizar la app.
    compileSdk = 36

    defaultConfig {
        // "com.example.*" es el paquete de plantilla de Android Studio y
        // Google Play no permite publicar con él. Se usa el dominio propio
        // en orden inverso, que es además la convención estándar de Android.
        applicationId = "es.maropal.simultrans"
        minSdk = 26
        targetSdk = 36
        // Permite fijar versionCode/versionName desde la línea de comandos
        // (gradle bundleRelease -PversionCode=2 -PversionName=1.1) para que
        // cada subida a Play Store use un código de versión distinto sin
        // tener que tocar este archivo cada vez.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        create("release") {
            // Se rellenan solo cuando el workflow de release los exporta
            // como variables de entorno (ver .github/workflows/build-release.yml).
            // El build de depuración normal (build.yml) no los necesita.
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                // El alias no es secreto: si el secreto falta o está vacío, se usa "simultrans".
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "simultrans"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

// Nuevo DSL de configuración del compilador de Kotlin (sustituye al
// antiguo "kotlinOptions" dentro del bloque android, que Kotlin 2.4.0 ya
// no admite). Vive a este nivel, junto a "android { }", no dentro de él.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Permite compilar contra litertlm-android aunque se compilara
        // con una versión de metadatos de Kotlin más nueva que la del
        // proyecto (ver conversación: desajuste de versión de metadatos).
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // LiteRT-LM Kotlin API — motor de inferencia on-device para Gemma
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
