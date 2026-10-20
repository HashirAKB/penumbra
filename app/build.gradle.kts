import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nightly builds are signed with a public test key (keystore/nightly.jks) so updates install
// over each other and your drawings are kept. Official releases should use a private key:
// set PENUMBRA_KEYSTORE, PENUMBRA_KEYSTORE_PASSWORD, PENUMBRA_KEY_ALIAS, PENUMBRA_KEY_PASSWORD.
val releaseKeystore: String? = System.getenv("PENUMBRA_KEYSTORE")

android {
    namespace = "io.github.hashirakb.penumbra"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hashirakb.penumbra"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("PENUMBRA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("PENUMBRA_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("nightly") {
            storeFile = rootProject.file("keystore/nightly.jks")
            storePassword = "penumbra-nightly"
            keyAlias = "nightly"
            keyPassword = "penumbra-nightly"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PENUMBRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PENUMBRA_KEY_ALIAS")
                keyPassword = System.getenv("PENUMBRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("nightly")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "nightly")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Pure-Kotlin unit tests for the drawing core (brushes, fill, journal, line art, lessons).
dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
