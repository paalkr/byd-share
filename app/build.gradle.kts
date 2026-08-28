import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing secrets are resolved per field, in priority order:
//   1. keystore.properties at the repo root (gitignored) — explicit override / CI.
//   2. the GNOME keyring via `secret-tool` (service=byd-share) — nothing on disk in
//      plaintext; set up once, then every `assembleRelease` signs with no manual step.
//   3. otherwise none → the release build falls back to the debug key so it still
//      builds (just not the key you publish updates with).
// See README "Building a signed release".
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingSecret(field: String): String? {
    keystoreProps.getProperty(field)?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching {
        val proc = ProcessBuilder("secret-tool", "lookup", "service", "byd-share", "field", field)
            .redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()
}

val relStoreFile = signingSecret("storeFile")
val relStorePassword = signingSecret("storePassword")
val relKeyAlias = signingSecret("keyAlias")
val relKeyPassword = signingSecret("keyPassword")
val hasReleaseSigning = relStoreFile != null && relStorePassword != null &&
    relKeyAlias != null && relKeyPassword != null && file(relStoreFile!!).exists()

android {
    namespace = "no.stink.bydshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.stink.bydshare"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(relStoreFile!!)
                storePassword = relStorePassword
                keyAlias = relKeyAlias
                keyPassword = relKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Minify stays off for now: the app is tiny and EncryptedSharedPreferences
            // (Tink) needs careful keep rules — not worth a broken release to shave a
            // couple of MB. Revisit with tested proguard rules if size ever matters.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
}
