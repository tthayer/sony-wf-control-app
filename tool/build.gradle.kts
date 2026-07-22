plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            // Shared Light dev keystore, sourced from the SDK submodule. Light's
            // build service ignores this (it builds with -DlightSdk.unsigned=true
            // and signs with its own key); it only matters for local installs.
            storeFile = rootProject.file("light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
}

// Unit-test dependencies for the pure-Kotlin Sony protocol library
// (:tool:testDebugUnitTest). The Light SDK plugin allowlists dependencies by
// coordinate prefix: `org.jetbrains.kotlin:kotlin-test*` and
// `org.jetbrains.kotlinx:kotlinx-coroutines*` are allowed, so kotlin-test-junit
// and kotlinx-coroutines-test pass. We do NOT declare junit:junit directly (it
// isn't allowlisted); kotlin-test-junit pulls it in transitively, which the
// plugin's resolved-dependency check permits as a transitive of an allowed dep.
dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
