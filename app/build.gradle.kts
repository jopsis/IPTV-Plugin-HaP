plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.orNull.isNullOrBlank() }

android {
    namespace = "com.streamvault.plugin.hap"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                storeType = "JKS"
            }
        }
    }

    defaultConfig {
        applicationId = "com.streamvault.plugin.hap"
        minSdk = 27
        targetSdk = 36
        versionCode = 14
        versionName = "1.3.3"

        // Not set here: abiFilters is unioned (not overridden) with each
        // productFlavor's own abiFilters below, so a defaultConfig value
        // here would silently defeat the per-flavor jniLibs filtering.
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // The bundled kubo (IPFS) binary and the AceServe engine both ship a
    // separate ~40-60MB blob per ABI (native libs under jniLibs/<abi>/, and
    // the AceServe zip under assets/aceserve/<abi>/): a single arm64-v8a +
    // armeabi-v7a "fat" APK carries both ABIs' copies of both, ~125MB. On
    // low-storage 32-bit Android TV boxes that is enough to make a clean
    // install fail with a generic "app not installed" (out of space), even
    // though every binary is present and valid.
    //
    // `android.splits.abi` only filters `lib/<abi>/` per output, not
    // `assets/`, so it can't drop the other ABI's AceServe zip. Product
    // flavors give each ABI its own `ndk.abiFilters` (filters jniLibs, same
    // as splits did) *and* its own asset source set (filters the AceServe
    // zip), while the "universal" flavor pulls in both ABIs' asset dirs to
    // keep a single fallback APK for anyone unsure of their device's ABI.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
        create("armv7") {
            dimension = "abi"
            ndk {
                abiFilters.clear()
                abiFilters += "armeabi-v7a"
            }
        }
        create("universal") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    sourceSets {
        getByName("universal") {
            assets.srcDirs("src/arm64/assets", "src/armv7/assets")
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
}

tasks.register("checkIpfsBinaries") {
    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        val missing = abis.filter { !file("src/main/jniLibs/$it/libipfs.so").exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing IPFS (kubo) binaries for: ${missing.joinToString()}. " +
                    "Run tools/fetch-kubo.sh from the repo root first (see README.md's IPFS section)."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkIpfsBinaries")
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

tasks.register("printVersionCode") {
    doLast {
        println(android.defaultConfig.versionCode)
    }
}
