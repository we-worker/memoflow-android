import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val sherpaVersion = "1.13.8"
val sileroVadUrls =
    listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx?download=1",
        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/271935959",
    )
val sileroVadSha256 =
    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
val generatedVadAssetsDir = layout.buildDirectory.dir("generated/silero-vad-assets")

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

val downloadSileroVad by tasks.registering {
    val output = generatedVadAssetsDir.map { it.file("silero_vad.onnx") }
    outputs.file(output)

    doLast {
        val file = output.get().asFile
        if (file.exists() && sha256(file.readBytes()) == sileroVadSha256) {
            return@doLast
        }

        file.parentFile.mkdirs()
        var lastError: Throwable? = null
        var downloaded: ByteArray? = null

        for (url in sileroVadUrls) {
            repeat(4) { attempt ->
                try {
                    val connection =
                        URI(url).toURL().openConnection().apply {
                            connectTimeout = 20_000
                            readTimeout = 60_000
                            setRequestProperty("User-Agent", "MemoFlow-Android-Build")
                            if (url.contains("api.github.com")) {
                                setRequestProperty("Accept", "application/octet-stream")
                                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                            }
                        }
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    val actual = sha256(bytes)
                    check(actual == sileroVadSha256) {
                        "silero_vad.onnx SHA-256 mismatch: expected " +
                            sileroVadSha256 + ", got " + actual
                    }
                    downloaded = bytes
                    return@repeat
                } catch (error: Throwable) {
                    lastError = error
                    Thread.sleep(1_000L * (attempt + 1))
                }
            }
            if (downloaded != null) break
        }

        val bytes = downloaded ?: throw GradleException(
            "Unable to download silero_vad.onnx after retries",
            lastError,
        )
        file.writeBytes(bytes)
    }
}

android {
    namespace = "com.memoflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.memoflow"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-demo"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedVadAssetsDir)

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadSileroVad)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.k2fsa:sherpa-onnx:" + sherpaVersion + "@aar")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
