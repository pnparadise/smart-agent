plugins {
    id("com.android.application")
    id("kotlin-android")
    // 必须加上这个才能用 @Serializable
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.smart"

    // 修复 1: 使用 compileSdk 并转为 Int
    compileSdk = flutter.compileSdkVersion.toInt()

    defaultConfig {
        applicationId = "com.smart"
        minSdk = flutter.minSdkVersion.toInt()
        targetSdk = flutter.targetSdkVersion.toInt()
        versionCode = flutter.versionCode.toInt()
        versionName = flutter.versionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 修复 2: 修正 jvmTarget 写法
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    // 修复 3: 使用双引号
    source = "../.."
}

dependencies {
    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AndroidX & UI
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WireGuard
    implementation("com.wireguard.android:tunnel:1.0.20250531")
}