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

    // De bibliotheken (spraak, stem, wekwoord) gecomprimeerd: de app wordt zo de helft kleiner om te downloaden
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("sherpa/jniLibs").get().asFile.path)
}

// Piper (de stem) draait op de tablet via sherpa-onnx. De versie 1.13 is de laatste met espeak-ng (de uitspraak);
// de bibliotheken komen bij het bouwen van GitHub, zodat ze niet in de repo hoeven.
val sherpaVersie = "1.13.8"
val haalSherpa by tasks.registering(Exec::class) {
    val doel = layout.buildDirectory.dir("sherpa").get().asFile
    outputs.dir(doel)
    onlyIf { !doel.resolve("jniLibs/armeabi-v7a/libsherpa-onnx-jni.so").exists() }
    commandLine("sh", "-c", "mkdir -p '$doel' && curl -sfL https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersie/" +
        "sherpa-onnx-v$sherpaVersie-android-static-link-onnxruntime.tar.bz2 | tar xj -C '$doel' jniLibs/armeabi-v7a jniLibs/arm64-v8a")
}
tasks.named("preBuild") { dependsOn(haalSherpa) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime)
    implementation(libs.vosk)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
