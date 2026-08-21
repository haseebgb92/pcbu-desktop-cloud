plugins {
    id("com.android.application")
}

android {
    namespace = "com.pcbiounlock.cloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pcbiounlock.cloud"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.4.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
