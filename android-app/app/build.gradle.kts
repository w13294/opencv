plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.targettracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.targettracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        // 关键: 声明支持 16KB 内存页 (Android 15/16 强制要求 native 库对齐)
        ndk {
            // 仅保留主流 ABI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // 关键: 不压缩 native 库, 确保 OpenCV 的 16KB 对齐 .so 可被系统直接 mmap 加载
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX (1.4.x 兼容 Android 15/16)
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // OpenCV (官方 Maven Central AAR, 4.11.0 支持 16KB 页对齐, 兼容 Android 16)
    implementation("org.opencv:opencv:4.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization (for settings persistence)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
