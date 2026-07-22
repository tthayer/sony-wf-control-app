import java.util.Properties

// The Light SDK is consumed as source via the `light-sdk/` git submodule
// (pinned to a specific upstream commit). Neither :sdk:client nor the
// `com.thelightphone.light-sdk` Gradle plugin are published as artifacts, so
// we graft the submodule's projects into this build as real projects and
// pull in its Gradle plugin via an included build. To bump the SDK:
//   git -C light-sdk fetch && git -C light-sdk checkout <ref>
//   git add light-sdk && git commit
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
// Reading the Light keyboard package (a transitive dep of :sdk:ui) from GitHub
// Packages needs a token with read:packages. Set gpr.user/gpr.key in
// local.properties or GH_PACKAGES_USER/GH_PACKAGES_TOKEN in the environment.
val ghUsername = localProperties.getProperty("gpr.user") ?: System.getenv("GH_PACKAGES_USER")
val ghPassword = localProperties.getProperty("gpr.key") ?: System.getenv("GH_PACKAGES_TOKEN")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages-Keyboard"
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                username = ghUsername
                password = ghPassword
            }
        }
    }
    // Reuse the SDK's own version catalog so this shell never drifts from
    // whatever the pinned submodule expects.
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "sony-wf-control-app"

// The `light.sdk` Gradle plugin lives in the submodule and is contributed as
// an included build (matches the SDK monorepo's own `includeBuild("plugin")`).
includeBuild("light-sdk/plugin")

// SDK library projects, sourced from the submodule. :sdk:client transitively
// needs :sdk:ui, :sdk:shared and :lint-rules; :sdk:server / :sdk:emulator are
// LightOS-side and intentionally omitted.
include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")
// :sdk is just a container project (no build script); point it at the
// submodule so Gradle doesn't look for a ./sdk dir at this repo's root.
include(":sdk")
project(":sdk").projectDir = file("light-sdk/sdk")
include(":sdk:shared")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")
include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")
include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

// The tool itself — this is the only dev-owned module, and the only thing
// Light's build/review pipeline extracts (tool/build.gradle.kts,
// tool/lighttool.toml, tool/src/main/**).
include(":tool")
