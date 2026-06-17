plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jon2g.aa_keyboard_unlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jon2g.aa_keyboard_unlock"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("versionName") as String? ?: "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    val releaseKeystore = rootProject.file("aa-keyboard-unlock.keystore")
    val releaseKeystorePassword = System.getenv("ANDROID_SIGNING_PASSWORD")

    signingConfigs {
        if (releaseKeystore.exists() && !releaseKeystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = "aa-keyboard-unlock"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "MODULE_DEBUG", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "MODULE_DEBUG", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        create("logging") {
            initWith(getByName("release"))
            buildConfigField("boolean", "MODULE_DEBUG", "true")
            versionNameSuffix = "-log"
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
