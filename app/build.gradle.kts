plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

kapt {
    correctErrorTypes = true
}

android {
    namespace = "com.albert.smartvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.albert.smartvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "6.0.0-BBR"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}"
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.hierynomus:sshj:0.37.0")
}
