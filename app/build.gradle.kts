plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.fightarena.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fightarena.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // remplace le stub org.json d'android.jar
    implementation(libs.androidx.activity.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.mlkit.pose.detection.accurate)
    implementation(libs.quickpose.core)
    implementation(libs.quickpose.mp)
    implementation(libs.protobuf.javalite)
    implementation(libs.guava)
    implementation(libs.onnxruntime)
    implementation(libs.flogger)
    implementation(libs.flogger.backend)
    implementation(libs.lifecycle.runtime.ktx)
}
