// Everything from java.* is imported, never fully qualified at the use site:
// inside a Kotlin DSL build script `java` resolves to the JavaPluginExtension,
// so `java.util.Base64` fails with "Unresolved reference: util". java.io.File
// is imported for the same reason plus one more — it is NOT one of Gradle's
// implicit Kotlin DSL imports, so `File(...)` without this line is a gamble.
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Release signing (docs/UPDATES.md §4).
//
// The APK MUST be signed with the same keystore every release or Android
// refuses the in-app update. CI hands the keystore in through env vars; a
// developer building locally gets the debug key and a loud reminder that the
// result cannot upgrade a released install.
//
// Env contract (identical names in .github/workflows/release.yml):
//   ANDROID_KEYSTORE_PATH      absolute path to the .jks the workflow decoded
//   ANDROID_KEYSTORE_BASE64    base64 of the .jks (used when PATH is unset)
//   ANDROID_KEYSTORE_PASSWORD
//   ANDROID_KEY_ALIAS
//   ANDROID_KEY_PASSWORD
//
// `local.properties` may carry the same four keys (without the BASE64 one) so
// a maintainer can produce a real release build on their own machine.
// ---------------------------------------------------------------------------

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

fun secret(name: String): String? =
    (System.getenv(name) ?: localProperties.getProperty(name))?.takeIf { it.isNotBlank() }

/** Materialises the keystore file, decoding ANDROID_KEYSTORE_BASE64 if needed. */
val releaseKeystoreFile: File? = run {
    val path = secret("ANDROID_KEYSTORE_PATH")
    if (path != null) {
        val file = File(path)
        if (file.isFile) return@run file
        logger.warn("ANDROID_KEYSTORE_PATH=$path does not exist — ignoring it.")
    }
    val base64 = secret("ANDROID_KEYSTORE_BASE64") ?: return@run null
    val decoded = File(layout.buildDirectory.get().asFile, "signing/sendro-release.jks")
    decoded.parentFile?.mkdirs()
    decoded.writeBytes(Base64.getMimeDecoder().decode(base64))
    decoded
}

val releaseSigningReady: Boolean =
    releaseKeystoreFile != null &&
        secret("ANDROID_KEYSTORE_PASSWORD") != null &&
        secret("ANDROID_KEY_ALIAS") != null &&
        secret("ANDROID_KEY_PASSWORD") != null

android {
    namespace = "com.sendro.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sendro.android"
        minSdk = 26
        targetSdk = 35

        // ---------------------------------------------------------------
        // scripts/bump_version.py rewrites exactly these two lines with the
        // regexes  ^\s*versionName\s*=\s*"..."  and  ^\s*versionCode\s*=\s*\d+
        // Keep the literal form (no interpolation, no helper function) or the
        // release will fail its version-drift check.
        // ---------------------------------------------------------------
        versionName = "1.0.0"
        versionCode = 10000

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseKeystoreFile
                storePassword = secret("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = secret("ANDROID_KEY_ALIAS")
                keyPassword = secret("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // R8 off on purpose for v1: the app is tiny, reflection-free
            // shrink bugs in a sideloaded binary are expensive to diagnose,
            // and nothing here is size-constrained. Revisit when it matters.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigningReady) {
                signingConfigs.getByName("release")
            } else {
                // Falls back to debug so `assembleRelease` still works on a
                // developer machine. Such an APK can NEVER upgrade a
                // release-signed install (UPDATES.md §4) — hence the warning.
                logger.lifecycle(
                    "Sendro: no release keystore in the environment — signing the " +
                        "release APK with the DEBUG key. It will not upgrade an " +
                        "existing release install."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Module-wide opt-ins rather than per-call-site @OptIn annotations.
        // Compose moves APIs between "experimental" and "stable" between
        // versions, and a missing opt-in is a hard compile error while an
        // unnecessary one is only a warning — so the whole module opts in once
        // and no call site has to know which side of the line an API is on.
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }

    lint {
        // CI runs `lint`; make it useful without being a gate that blocks a
        // sideload build over a cosmetic warning.
        abortOnError = false
        warningsAsErrors = false
        checkDependencies = false
        htmlReport = true
        xmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
