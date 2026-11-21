import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Офлайн-модель Vosk для русского языка (Apache-2.0). В git не хранится: скачивается при сборке
// и проверяется по SHA-256, затем целиком упаковывается в APK. Само приложение в сеть не ходит.
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
val voskModelSha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"

android {
    namespace = "ru.ivanivorontsov.nevedis"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ru.ivanivorontsov.nevedis"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            // Ключ лежит вне репозитория. Путь можно переопределить переменной NEVEDIS_KEY_DIR.
            val keyDir = File(System.getenv("NEVEDIS_KEY_DIR") ?: "/home/box/keep-ema/keys/ne-vedis")
            val passFile = File(keyDir, "password.txt")
            if (passFile.isFile) {
                val props = passFile.readLines().mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                }.toMap()
                storeFile = File(keyDir, props["storeFile"] ?: "ne-vedis.jks")
                storePassword = props["storePassword"]
                keyAlias = props["keyAlias"] ?: "nevedis"
                keyPassword = props["keyPassword"] ?: props["storePassword"]
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.isFile == true) signingConfig = releaseSigning
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libvosk.so", "**/libjnidispatch.so")
        }
        resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    }
    androidResources { noCompress += listOf("zip") }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val downloadVoskModel by tasks.registering {
    val target = layout.projectDirectory.file("src/main/assets/vosk-model-small-ru.zip").asFile
    outputs.file(target)
    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    outputs.upToDateWhen { target.isFile && sha256(target) == voskModelSha256 }
    doLast {
        if (target.isFile && sha256(target) == voskModelSha256) return@doLast
        target.parentFile.mkdirs()
        val local = System.getenv("NEVEDIS_VOSK_ZIP")?.let(::File)
        if (local != null && local.isFile) local.copyTo(target, overwrite = true)
        else URI(voskModelUrl).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        val actual = sha256(target)
        check(actual == voskModelSha256) { "Vosk model checksum mismatch: $actual" }
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
