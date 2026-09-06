plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Aucune clé de fournisseur n'est injectée dans l'APK : elles vivent toutes sur le
// cerveau. La tablette ne détient qu'une adresse et un jeton d'appairage, saisis à
// l'installation et chiffrés par l'AndroidKeyStore.

android {
    namespace = "com.brutus.pepper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brutus.pepper"
        minSdk = 23
        targetSdk = 35
        versionCode = 24
        versionName = "0.11.1"

        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.aldebaran:qisdk:1.7.5")
    implementation("com.aldebaran:qisdk-design:1.7.5")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Vosk offline speech recognition — wake-word detection (on-device, restricted grammar)
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Plus aucun SDK de fournisseur : la transcription et la conversation passent par
    // le cerveau, qui est le seul à détenir des clés.
    testImplementation("junit:junit:4.13.2")
}
