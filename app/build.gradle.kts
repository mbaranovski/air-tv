plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Overridable so a release build can take its version from the git tag:
//   ./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.airtv.receiver"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.airtv.receiver"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // A stable release key, supplied by CI (or locally) through these properties. Without
    // it every build would be signed with a throwaway debug key, and releases could never
    // be installed as updates over one another.
    val keystorePath = (findProperty("signingKeystore") as String?)
        ?: System.getenv("SIGNING_KEYSTORE")
    val keystorePassword = (findProperty("signingKeystorePassword") as String?)
        ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val keyAlias = (findProperty("signingKeyAlias") as String?)
        ?: System.getenv("SIGNING_KEY_ALIAS") ?: "airtv"
    val keyPassword = (findProperty("signingKeyPassword") as String?)
        ?: System.getenv("SIGNING_KEY_PASSWORD") ?: keystorePassword

    signingConfigs {
        if (keystorePath != null && keystorePassword != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key for local builds with no keystore configured.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
