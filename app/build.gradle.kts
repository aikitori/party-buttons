plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.aikitori.partybuttons"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.aikitori.partybuttons"
        minSdk = 26
        targetSdk = 36
        // in der CI: -PversionCode=<Laufnummer> -PversionName=0.<Laufnummer>
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1.0"
    }

    // fester Schlüssel aus den Repo-Secrets (scripts/keystore-einrichten.sh), damit Updates über die installierte App gehen
    val keystore = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Screenshot-Tests mit Roborazzi laufen unter Robolectric und brauchen die echten Ressourcen
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            // doppelte Lizenz-/Index-Dateien aus den Ktor-Abhängigkeiten
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":server"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

// ./gradlew :app:recordRoborazziDebug legt die Bilder hier ab
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}
