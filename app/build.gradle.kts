import java.io.File
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.vibenfs"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.vibenfs"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ponytail: real devices + emulator only; add armeabi-v7a if 32-bit phones matter
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                // ponytail: expand leading ~ ourselves - Gradle's file() treats it as a literal dir name
                fun prop(key: String) = requireNotNull(keystoreProps.getProperty(key)) { "keystore.properties: $key missing" }
                storeFile = prop("storeFile").let { p ->
                    if (p.startsWith("~/")) File(System.getProperty("user.home"), p.drop(2)) else rootProject.file(p)
                }
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Upload key when keystore.properties is present (required for Play), else
            // debug-signed so CI and sideload builds keep working.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = true }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.glance.appwidget)
    // Glance pulls WorkManager 2.7, which brings Room 2.2: its reflection-built WorkDatabase did not
    // survive R8 full mode, so minified release builds crashed at startup. Pinning a current
    // WorkManager (Room 2.7) fixes it; verified on a release build.
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
