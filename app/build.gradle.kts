import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

apply(plugin = "org.jetbrains.kotlin.kapt")

/*
 * Permanent release signing configuration for My Drive.
 *
 * The distributed release APK must always be signed with the permanent
 * keystore (mydrive-release.jks, alias "mydrive") so that future APKs can
 * update an installed copy of the app. Credentials are never hardcoded here.
 * They are resolved, in order, from:
 *
 *   1. Environment variables (used by GitHub Actions / CI):
 *        MYDRIVE_KEYSTORE_FILE
 *        MYDRIVE_KEYSTORE_PASSWORD
 *        MYDRIVE_KEY_ALIAS
 *        MYDRIVE_KEY_PASSWORD
 *
 *   2. A local `keystore.properties` file at the repository root (git-ignored):
 *        storeFile=...
 *        storePassword=...
 *        keyAlias=...
 *        keyPassword=...
 *
 * The keystore file itself is never committed to Git.
 */
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingValue("MYDRIVE_KEYSTORE_FILE", "storeFile")
val releaseKeystorePassword = signingValue("MYDRIVE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("MYDRIVE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("MYDRIVE_KEY_PASSWORD", "keyPassword")

val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.mydrive.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mydrive.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"https://gpiuxcdjmrzcouhjapcs.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdwaXV4Y2RqbXJ6Y291aGphcGNzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkyMjY1MTMsImV4cCI6MjEwNDgwMjUxM30.Tm6IPdc5mlSH0J76mbmXut4nd33JnyAG982o30sOu2A\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Debug builds keep Android's standard debug keystore and are only for
        // local testing. They are never distributed and never attached to a Release.
        getByName("debug") {
            isMinifyEnabled = false
        }
        // Distributed builds (GitHub Releases, manual installs) MUST always use the
        // permanent My Drive release key. No applicationIdSuffix is set, so the
        // package identity stays com.mydrive.app for both build types.
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }
}

// Fail fast when a release build is attempted without the permanent signing key,
// instead of silently producing an unsigned APK. Debug builds are unaffected.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "Release signing is not configured. Provide the MYDRIVE_KEYSTORE_FILE, " +
                    "MYDRIVE_KEYSTORE_PASSWORD, MYDRIVE_KEY_ALIAS and MYDRIVE_KEY_PASSWORD " +
                    "environment variables (CI), or a git-ignored keystore.properties file " +
                    "at the repository root. Refusing to build an unsigned release APK.",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    add("kapt", libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
