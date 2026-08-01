plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Build-time bytecode fix for the Android 15 removeFirst()/removeLast() crash (see build-logic).
    id("buildlogic.removefirstlast-fix")
}

import java.util.Properties

// Prefer signing.properties; fall back to local.properties (both gitignored).
val keystoreProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
val localPropertiesFile = rootProject.file("local.properties")
when {
    signingPropertiesFile.exists() -> keystoreProperties.load(signingPropertiesFile.reader())
    localPropertiesFile.exists() -> keystoreProperties.load(localPropertiesFile.reader())
}

fun resolveStoreFile(raw: String?): java.io.File? {
    if (raw.isNullOrBlank() || raw == ".") return null
    val asIs = rootProject.file(raw)
    return when {
        asIs.isFile -> asIs
        else -> rootProject.file(raw.trimStart('/', '\\')).takeIf { it.isFile }
    }
}

val agoraStoreFile = resolveStoreFile(keystoreProperties.getProperty("storeFile"))
val agoraStorePassword = keystoreProperties.getProperty("storePassword").orEmpty()
val agoraKeyAlias = keystoreProperties.getProperty("keyAlias").orEmpty()
val agoraKeyPassword = keystoreProperties.getProperty("keyPassword").orEmpty()
val hasAgoraKeystore = agoraStoreFile != null &&
    agoraStorePassword.isNotBlank() &&
    agoraKeyAlias.isNotBlank() &&
    agoraKeyPassword.isNotBlank()

if (!hasAgoraKeystore) {
    logger.warn(
        "Agora: no custom keystore configured (signing.properties / local.properties). " +
            "debug+release will fall back to the Android debug keystore — install will not match a " +
            "previous custom-signed build."
    )
}

android {
    namespace = "com.newoether.agora"
    compileSdk {
        version = release(36)
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
            applicationId = "com.newoether.agora"
            minSdk = 24
            targetSdk = 36
            versionCode = 30
                                    versionName = "1.4.2"

            // Which CI run produced this APK, so the in-app CI update channel can tell
            // "newer build" from "same build" — every CI build shares one versionName,
            // so semver comparison is useless there. 0 = built locally, not by CI.
            buildConfigField(
                "int",
                "CI_RUN_NUMBER",
                (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull()?.toString() ?: "0"
            )

            // Native code (llama/proot) is currently arm64-only; non-arm64 APKs ship
            // without those .so files (cloud chat still works; local/sandbox needs arm64).
            ndk {
                // Restrict CMake/NDK compile targets; packaging splits still emit 5 APKs.
                abiFilters += listOf("arm64-v8a")
            }

            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf("-DANDROID_STL=c++_shared")
                    targets += listOf("agora_llama", "agora_proot")
                }
            }
        }

        // universal APK + per-ABI APKs (arm64-v8a / armeabi-v7a / x86 / x86_64) = 5 outputs.
        // Note: only arm64-v8a currently includes agora native libs (see ndk.abiFilters).
        splits {
            abi {
                isEnable = true
                reset()
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = true
            }
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

    signingConfigs {
        // Shared custom keystore for debug + release so installDebug can overwrite
        // a previously installed release (and vice versa) without SIGNATURE_MISMATCH.
        if (hasAgoraKeystore) {
            getByName("debug") {
                storeFile = agoraStoreFile
                storePassword = agoraStorePassword
                keyAlias = agoraKeyAlias
                keyPassword = agoraKeyPassword
            }
            create("release") {
                storeFile = agoraStoreFile
                storePassword = agoraStorePassword
                keyAlias = agoraKeyAlias
                keyPassword = agoraKeyPassword
            }
        } else {
            create("release") {
                // Keep a named config so buildTypes can reference it; falls through to debug.
                initWith(getByName("debug"))
            }
        }
    }

    val agoraSigning = if (hasAgoraKeystore) {
        signingConfigs.getByName("release")
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        debug {
            signingConfig = agoraSigning
        }
        release {
            signingConfig = agoraSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Extract .so files to disk for ProcessBuilder exec (Kai approach)
    @Suppress("UnstableApiUsage")
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Proot binaries (libproot_exec.so, libproot_loader.so, libtalloc.so) are
// built via GNUmakefile (see .build-proot/) and placed directly in jniLibs.
// No CMake target is needed — the binaries are manually managed prebuilts.
// talloc is built with SONAME=libtalloc.so (no version) so AGP packaging works.

tasks.register<Copy>("copyReleaseApk") {
    from("build/outputs/apk/release")
    into("release")
    include("*.apk")
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("copyReleaseApk")
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.markdown)
    implementation(libs.jetbrains.markdown)
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.lottie.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    implementation(libs.mcp.kotlin.sdk.client)
    implementation(libs.ktor.client.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Bounds/cancellation tests for model-catalog fetches need a real socket that can accept a
    // connection and then stay silent; that behaviour cannot be faked with a mocked OkHttp client.
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile") || name.contains("BaselineProfile") || name.contains("baselineProfile")) {
        enabled = false
    }
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) {
        enabled = false
    }
}
