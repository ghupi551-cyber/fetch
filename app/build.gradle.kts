plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dsh.videolink"
    compileSdk {
        version = release(36)
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.dsh.videolink"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "5.0.0"

        // yt-dlp 引擎（youtubedl-android）自带 Python 运行时，只保留 arm64 以控制体积
        ndk {
            abiFilters += "arm64-v8a"
        }

    }

    // 签名信息从 gradle.properties（本机）读取，**不写进源码**。
    // 别人克隆这份源码时没有这些属性，会自动跳过签名（构建出未签名包）。
    val releaseStoreFile = (project.findProperty("videolink.storeFile") as String?)?.takeIf { it.isNotBlank() }
    val releaseStorePassword = (project.findProperty("videolink.storePassword") as String?)?.takeIf { it.isNotBlank() }
    val releaseKeyAlias = (project.findProperty("videolink.keyAlias") as String?)?.takeIf { it.isNotBlank() }
    val releaseKeyPassword = (project.findProperty("videolink.keyPassword") as String?)?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            file(releaseStoreFile).exists()
        ) {
            create("dshRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("dshRelease")?.let { signingConfig = it }
            vcsInfo.include = false
        }
        debug {
            signingConfigs.findByName("dshRelease")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android 需要从 nativeLibraryDir 读到 libpython.zip.so，
        // 所以必须让安装时把 .so 解压出来（useLegacyPackaging = extractNativeLibs）。
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.ripple)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kyant.shapes)
    implementation(libs.kyant.backdrop)

    // yt-dlp 引擎：支持 YouTube / TikTok / Instagram / X 等 1800+ 站点
    implementation(libs.youtubedl.android)
    // 内置 ffmpeg：用于音视频合并、音频抽取
    implementation(libs.youtubedl.android.ffmpeg)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
}
