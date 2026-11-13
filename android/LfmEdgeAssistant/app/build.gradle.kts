plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.snapdragon.edge"
    compileSdk = 33
    buildToolsVersion = "35.0.0"
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "ai.snapdragon.edge"
        minSdk = 29
        ndk { abiFilters.add("arm64-v8a") }
        targetSdk = 33
        versionCode = 8
        versionName = "3.3.2"
        testInstrumentationRunner = "ai.snapdragon.edge.VoiceIntegrationProbe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


tasks.named("preBuild") {
    val speechModelVersion = layout.projectDirectory.file("src/main/assets/speech/vosk-en/asset-version").asFile
    doFirst {
        check(speechModelVersion.exists()) {
            "Run python3 scripts/prepare_speech.py from the Android project before building."
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
