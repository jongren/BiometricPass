import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

fun signingProp(key: String, envName: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(envName)

val installedNdkVersion: String? = run {
    val sdkDir = Properties().apply {
        val lp = rootProject.file("local.properties")
        if (lp.exists()) lp.inputStream().use { load(it) }
    }.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    sdkDir?.let { dir ->
        File(dir, "ndk").listFiles()
            ?.filter { it.isDirectory && File(it, "source.properties").exists() }
            ?.map { it.name }
            ?.maxOrNull()
    }
}

ksp {
    // Export Room schema JSON so every future migration can be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.biometricpass.app"
    compileSdk = 36
    if (installedNdkVersion != null) {
        ndkVersion = installedNdkVersion
    }

    defaultConfig {
        applicationId = "com.biometricpass.app"
        minSdk = 29
        targetSdk = 36
        // Versioning policy: increment versionCode monotonically for every Play/APK release;
        // use MAJOR.MINOR.PATCH for versionName.
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (installedNdkVersion != null) {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    signingConfigs {
        create("release") {
            // Signing secrets are loaded from untracked `keystore.properties` (project root)
            // or from environment variables, never stored in version control.
            signingProp("storeFile", "BP_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingProp("storePassword", "BP_STORE_PASSWORD")
            keyAlias = signingProp("keyAlias", "BP_KEY_ALIAS")
            keyPassword = signingProp("keyPassword", "BP_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // Separate applicationId so debug builds (screenshots/testing) can coexist side-by-side
            // with production release builds without uninstalling or wiping the user's real vault.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    // Compress .dex only in debug APKs (keeps unminified debug builds ~5 MB instead of ~60 MB),
    // while keeping release .dex uncompressed for faster ART mmap cold start.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.dex.useLegacyPackaging.set(false)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Biometric & Autofill
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.autofill)

    // QR Code (ZXing + CameraX for offline QR scanning)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Instrumentation tests (Room schema / migration & auto-lock policy)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
