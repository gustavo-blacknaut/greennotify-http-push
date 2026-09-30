plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.blacknaut.greennotify"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.blacknaut.greennotify"
        minSdk = 24
        targetSdk = 37
        versionCode = 10
        versionName = "1.0.9"
    }

    // Assinatura só via variáveis de ambiente: a keystore e a senha nunca entram no repositório.
    val keystorePath = System.getenv("GREENNOTIFY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GREENNOTIFY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GREENNOTIFY_KEY_ALIAS") ?: "greennotify"
                keyPassword = System.getenv("GREENNOTIFY_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)
    implementation(libs.work.runtime)
}
