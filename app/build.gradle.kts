
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "co.edu.unal.tictactoe"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "co.edu.unal.tictactoe"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // ==========================================
    // DEPENDENCIAS ORIGINALES DE ANDROID
    // ==========================================

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)

    // ==========================================
    // FIREBASE - RETO 7: TRIQUI ONLINE
    // ==========================================

    // Firebase BoM: administra las versiones
    // compatibles de las bibliotecas Firebase
    implementation(
        platform("com.google.firebase:firebase-bom:34.4.0")
    )

    // Cloud Firestore:
    // Almacena partidas y sincroniza jugadas
    implementation(
        "com.google.firebase:firebase-firestore"
    )

    // Firebase Authentication:
    // Identifica jugadores de forma anónima
    implementation(
        "com.google.firebase:firebase-auth"
    )

    // ==========================================
    // DEPENDENCIAS PARA PRUEBAS
    // ==========================================

    testImplementation(libs.junit)

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )
}
