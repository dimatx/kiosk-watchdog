import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is configured only when a keystore is supplied, either via
// environment variables (CI) or a local keystore.properties file. Otherwise the
// release build falls back to the debug key so the project always builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = secret("KEYSTORE_FILE")
val hasReleaseKeystore = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "com.shymoose.wifiwatchdog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shymoose.wifiwatchdog"
        minSdk = 27
        // Must stay <= 28: WifiManager.setWifiEnabled() is a no-op for apps
        // targeting API 29+, and that call is the core of the recovery ladder.
        targetSdk = 28
        versionCode = 21
        versionName = "1.11.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        viewBinding = true
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

// PreferenceDefaultsTest reads root_preferences.xml straight off disk, which Gradle cannot
// infer from the classpath. Without this the test task stays UP-TO-DATE when a default
// drifts and the guardrail silently never runs.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/res/xml")).withPropertyName("settingsXml")
}
