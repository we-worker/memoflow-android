import java.io.File
import java.net.HttpURLConnection
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

val fireRedSourceCommit = "c30ec49e8cc69642b0ee65362eba11b9d11c6e54"
val fireRedConvertedCommit = "8e9d2694e7e55abeda4bfdb14f31e56dc0d4c95e"
val ncnnVersion = "20260526"
val ncnnAndroidSha256 =
    "85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796"

val generatedFireRedAssetsDir =
    layout.buildDirectory.dir("generated/firered-vad-assets")
val generatedFireRedSourceDir =
    layout.buildDirectory.dir("generated/firered-native/source")
val generatedNcnnDir =
    layout.buildDirectory.dir("generated/firered-native/ncnn")

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun downloadWithRetries(
    urls: List<String>,
    target: File,
    expectedSha256: String? = null,
    minBytes: Long = 1L,
) {
    var lastError: Throwable? = null

    for (url in urls) {
        repeat(4) { attempt ->
            try {
                target.parentFile.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.delete()

                val connection =
                    URI(url).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 180_000
                        setRequestProperty("User-Agent", "MemoFlow-Android-Build")
                        if (url.contains("api.github.com")) {
                            setRequestProperty("Accept", "application/octet-stream")
                            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                        }
                        if (this is HttpURLConnection) {
                            instanceFollowRedirects = true
                        }
                    }

                connection.getInputStream().buffered().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }

                check(tmp.length() >= minBytes) {
                    "Downloaded file is too small: " + tmp.length() + " bytes from " + url
                }

                if (expectedSha256 != null) {
                    val actual = sha256File(tmp)
                    check(actual.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 mismatch for " + target.name +
                            ": expected " + expectedSha256 + ", got " + actual
                    }
                }

                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) {
                    "Unable to move downloaded file to " + target
                }
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(1_000L * (attempt + 1))
            }
        }
    }

    throw GradleException(
        "Unable to download " + target.name + " after retries",
        lastError,
    )
}

val downloadSileroVad by tasks.registering {
    val output = generatedVadAssetsDir.map { it.file("silero_vad.onnx") }
    outputs.file(output)

    doLast {
        val file = output.get().asFile
        if (file.exists() && sha256File(file) == sileroVadSha256) {
            return@doLast
        }

        downloadWithRetries(
            urls = sileroVadUrls,
            target = file,
            expectedSha256 = sileroVadSha256,
            minBytes = 100_000L,
        )
    }
}

val prepareFireRedVad by tasks.registering {
    val assetsDir = generatedFireRedAssetsDir.get().asFile
    val sourceDir = generatedFireRedSourceDir.get().asFile
    val ncnnDir = generatedNcnnDir.get().asFile

    outputs.dirs(assetsDir, sourceDir, ncnnDir)

    doLast {
        val workDir = layout.buildDirectory.dir("generated/firered-native/downloads").get().asFile
        workDir.mkdirs()

        val sourceMarker =
            sourceDir.walkTopDown()
                .firstOrNull { it.name == "firered_vad_stream_packed.cpp" }
        if (sourceMarker == null) {
            sourceDir.deleteRecursively()
            sourceDir.mkdirs()

            val sourceZip = File(workDir, "firered-source.zip")
            downloadWithRetries(
                urls =
                    listOf(
                        "https://github.com/FireRedTeam/FireRedVAD/archive/" +
                            fireRedSourceCommit + ".zip",
                    ),
                target = sourceZip,
                minBytes = 100_000L,
            )
            project.copy {
                from(zipTree(sourceZip))
                into(sourceDir)
            }
            sourceZip.delete()
        }

        val ncnnConfig =
            ncnnDir.walkTopDown()
                .firstOrNull { it.name == "ncnnConfig.cmake" }
        if (ncnnConfig == null) {
            ncnnDir.deleteRecursively()
            ncnnDir.mkdirs()

            val ncnnZip = File(workDir, "ncnn-android.zip")
            downloadWithRetries(
                urls =
                    listOf(
                        "https://github.com/Tencent/ncnn/releases/download/" +
                            ncnnVersion + "/ncnn-" + ncnnVersion + "-android.zip",
                    ),
                target = ncnnZip,
                expectedSha256 = ncnnAndroidSha256,
                minBytes = 10L * 1024L * 1024L,
            )
            project.copy {
                from(zipTree(ncnnZip))
                into(ncnnDir)
            }
            ncnnZip.delete()
        }

        val rawBase =
            "https://raw.githubusercontent.com/lhwcv/FireRedVAD-NCNN-streaming/" +
                fireRedConvertedCommit + "/convert/out/"

        val models =
            mapOf(
                "non_stream/firered_vad_non_stream.ncnn.param" to
                    Pair("firered_vad_non_stream.ncnn.param", 1_000L),
                "non_stream/firered_vad_non_stream.ncnn.bin" to
                    Pair("firered_vad_non_stream.ncnn.bin", 1_000_000L),
                "non_stream/cmvn_means.bin" to Pair("cmvn_means.bin", 100L),
                "non_stream/cmvn_istd.bin" to Pair("cmvn_istd.bin", 100L),
                "stream/firered_vad_packed_cache_stream.ncnn.param" to
                    Pair("firered_vad_packed_cache_stream.ncnn.param", 1_000L),
                "stream/firered_vad_packed_cache_stream.ncnn.bin" to
                    Pair("firered_vad_packed_cache_stream.ncnn.bin", 1_000_000L),
                "stream/cmvn_means_stream.bin" to Pair("cmvn_means_stream.bin", 100L),
                "stream/cmvn_istd_stream.bin" to Pair("cmvn_istd_stream.bin", 100L),
            )

        for ((relativePath, sourceInfo) in models) {
            val target = File(assetsDir, "firered/" + relativePath)
            if (target.exists() && target.length() >= sourceInfo.second) continue

            downloadWithRetries(
                urls = listOf(rawBase + sourceInfo.first),
                target = target,
                minBytes = sourceInfo.second,
            )
        }
    }
}

android {
    namespace = "com.memoflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.memoflow"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-demo"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments +=
                    listOf(
                        "-DFIRERED_SRC_BUNDLE=" +
                            generatedFireRedSourceDir.get().asFile.absolutePath,
                        "-DNCNN_BUNDLE_DIR=" +
                            generatedNcnnDir.get().asFile.absolutePath,
                    )
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedVadAssetsDir)
    sourceSets["main"].assets.srcDir(generatedFireRedAssetsDir)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadSileroVad, prepareFireRedVad)
}

tasks.matching {
    it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake") ||
        it.name.startsWith("externalNativeBuild")
}.configureEach {
    dependsOn(prepareFireRedVad)
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
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("com.k2fsa:sherpa-onnx:" + sherpaVersion + "@aar")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
