plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.ldxw.landrop"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.ldxw.landrop"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    // 固定签名：keystore 提交在仓库里，密码走 CI secret（KEYSTORE_PASSWORD）。
    // 本地没有密码时回退到 debug 签名，方便随意构建。
    val ksFile = rootProject.file("keystore/landrop-release.p12")
    val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val hasReleaseKey = ksFile.exists() && ksPass.isNotEmpty()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "landrop"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
}
