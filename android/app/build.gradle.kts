import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Ondertekening: op GitHub uit de geheime instellingen, lokaal uit
// ../../AndroidStudioProjects/haard-release.properties (staat buiten de repo).
val localSigning = Properties().apply {
    val file = rootProject.file("../../AndroidStudioProjects/haard-release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signing(name: String): String? = System.getenv(name) ?: localSigning.getProperty(name)

// Elke build op GitHub krijgt een hoger versienummer, zodat Android hem als update ziet.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "nl.timvanbergen.haard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "nl.timvanbergen.haard"
        minSdk = 26 // Fire HD 8 (2020) draait Fire OS 7 = Android 9
        targetSdk = 37
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            signing("HAARD_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = signing("HAARD_KEYSTORE_PASSWORD")
            keyAlias = signing("HAARD_KEY_ALIAS")
            keyPassword = signing("HAARD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime)
}
