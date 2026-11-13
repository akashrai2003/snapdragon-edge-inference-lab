plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.snapdragon.edge"
    compileSdk = 33

    defaultConfig {
        applicationId = "ai.snapdragon.edge"
        minSdk = 29
        targetSdk = 33
        versionCode = 5
        versionName = "3.2.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

