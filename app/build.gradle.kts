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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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
    implementation(libs.cardview)
}
