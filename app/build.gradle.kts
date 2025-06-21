plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.ceparsivi"
    // --- DÜZELTME 1 ---
    // compileSdk'yı 35 (önizleme) yerine 34 (en son kararlı sürüm) olarak ayarlamak daha stabildir.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ceparsivi"
        minSdk = 24
        // --- DÜZELTME 2 ---
        // targetSdk da compileSdk ile uyumlu olmalı.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // --- DÜZELTME 3 ---
        // Java versiyonunu modern projeler için standart olan 17'ye yükseltmek iyi bir pratiktir.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Glide Kütüphanesi
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // --- DÜZELTME 4 ---
    // lifecycle-runtime-ktx versiyonunu daha standart bir sürümle güncelledim.
    // Projenizdeki libs.versions.toml dosyasında farklı bir sürüm olabilir, bu daha genel bir örnektir.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}