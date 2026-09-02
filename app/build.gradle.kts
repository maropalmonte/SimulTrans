import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.simultrans"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.simultrans"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
