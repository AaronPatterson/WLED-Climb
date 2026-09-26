plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// The release keystore never goes in the repo. On Android the signing
// certificate *is* the app's identity: updates are only accepted if they're
// signed by the same key, so anyone holding it can ship a build that devices
// trust as genuine - and losing it means never being able to update the
// installs already out there.
//
// Credentials come from Gradle properties in ~/.gradle/gradle.properties,
// deliberately outside the project rather than in a gitignored file beside
// it. gitignore only defends against git; a secret inside the project folder
// is still reachable by a zip of the directory, a backup tool pointed at the
// repo, or a stray `git add -f`. Outside the tree none of those touch it.
//
// The same names also work as -P flags or ORG_GRADLE_PROJECT_* environment
// variables, so CI needs no separate mechanism. See docs/releasing.md.
//
// Checked as a set rather than just the keystore path. Setting the path but
// leaving a password blank is a mistake that has already happened once here,
// and it surfaces much later as a cryptic "keystore password was incorrect"
// from the packaging task. A blank value counts as missing for that reason.
val signingCredentials = listOf(
    "WLED_CLIMB_STORE_FILE",
    "WLED_CLIMB_STORE_PASSWORD",
    "WLED_CLIMB_KEY_ALIAS",
    "WLED_CLIMB_KEY_PASSWORD"
).associateWith { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }

val missingCredentials = signingCredentials.filterValues { it == null }.keys
val canSignRelease = missingCredentials.isEmpty()

// An unsigned release APK cannot be installed on anything, so producing one
// is a mistake unless it was asked for. The convention of letting it build
// anyway exists to keep a project buildable by contributors who will never
// have the key - this one has no contributors and a single release machine,
// so the cost (a build that reports success and ships nothing installable)
// buys nothing. Opt in deliberately with -PallowUnsigned=true.
val allowUnsigned = providers.gradleProperty("allowUnsigned").orNull?.toBoolean() ?: false

/**
 * Which commit a debug build came from, appended to its version name.
 *
 * Every test build so far has called itself 0.7.0-debug, so a phone could not
 * say which of them it was holding - which defeats the point of showing the
 * version at all. Release builds are identified by their version number and
 * do not get this.
 *
 * A dirty tree is marked, because most test builds are made from one and the
 * hash alone would name a commit the build does not actually match.
 */
val debugBuildId: String = try {
    fun git(vararg args: String) = providers.exec {
        commandLine(listOf("git") + args)
    }.standardOutput.asText.get().trim()

    val hash = git("rev-parse", "--short=7", "HEAD")
    val dirty = if (git("status", "--porcelain").isNotEmpty()) ".dirty" else ""
    "+$hash$dirty"
} catch (e: Exception) {
    // A build from a source drop rather than a checkout still has to work.
    ""
}

android {
    namespace = "com.wledclimb.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wledclimb.app"
        minSdk = 26
        targetSdk = 36
        // Bump both when cutting a release. versionCode is what decides
        // "is there a newer build?" - Android rejects an update whose code is
        // lower than what's installed, and Obtainium/Play won't offer one that
        // isn't higher. Day-to-day `adb install -r` tolerates an unchanged
        // code, so this only has to move when a build actually goes out.
        // Forgetting shows up as an update that silently doesn't apply, which
        // is why versionName is on screen in the app.
        versionCode = 8
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // v3 is what makes key rotation possible at all (API 28+): a later
            // build can carry a lineage proving a new key was authorised by
            // this one. It is the only escape hatch if this key is ever
            // compromised - it does nothing for a key that is simply lost,
            // since rotating requires the old key to sign off on the new.
            // v1 stays off: minSdk is 26 and v2 already covers API 24+.
            enableV3Signing = true

            if (canSignRelease) {
                storeFile = rootProject.file(signingCredentials.getValue("WLED_CLIMB_STORE_FILE")!!)
                storePassword = signingCredentials.getValue("WLED_CLIMB_STORE_PASSWORD")
                keyAlias = signingCredentials.getValue("WLED_CLIMB_KEY_ALIAS")
                keyPassword = signingCredentials.getValue("WLED_CLIMB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // A separate app, not a variant of the same one. Debug builds are
            // signed with the throwaway debug key, so without this they cannot
            // install over a release build or be replaced by one - every swap
            // between a test build and the published release would cost an
            // uninstall, and once routes are saved that means losing them.
            //
            // It also keeps Obtainium out of the way: it manages
            // com.wledclimb.app and cannot see this package at all.
            //
            // Separate applicationId means separate storage, so the debug app
            // asks for a controller address of its own. That is the point - a
            // test build can be aimed somewhere else without disturbing the
            // build anyone else is using.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug$debugBuildId"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attached only when there is something to sign with. The build
            // fails before reaching here otherwise - see verifyReleaseSigning.
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8. Needed for BuildConfig.VERSION_NAME,
        // which the app shows so you can tell which build is on which device.
        buildConfig = true
    }

    testOptions {
        // android.util.Log is a stub in local unit tests and throws "not mocked"
        // by default - which would fail any test covering a code path that logs.
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the real android.jar rather than the stub, for the
        // tests that run SQLite. The rest of the suite is unaffected: it never
        // touches a framework class, so it neither gains nor loses anything.
        unitTests.isIncludeAndroidResources = true
    }
}

// Scoped to the tasks that actually package a release rather than checked at
// configuration time, so a machine without the keystore can still run `test`,
// `assembleDebug` and IDE sync - none of which have any business caring about
// signing. Covers bundleRelease too: an unsigned AAB is the same mistake.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        if (!canSignRelease && !allowUnsigned) {
            throw GradleException(
                """
                |Release signing credentials missing: ${missingCredentials.joinToString(", ")}
                |
                |These belong in ~/.gradle/gradle.properties - see docs/releasing.md.
                |A blank value counts as missing, which is the usual cause.
                |
                |Building unsigned has to be asked for, because the result cannot
                |be installed on any device:
                |
                |    gradlew assembleRelease -PallowUnsigned=true
                """.trimMargin()
            )
        }
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verifyReleaseSigning) }

// Room writes the schema as JSON so migrations can be diffed against a real
// previous version rather than reconstructed from memory.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // List beside editor on a tablet, list then editor on a phone, from one
    // implementation - see docs/navigation.md. Versioned independently of the
    // Compose BOM, so these carry explicit versions; 1.3.0 is the current
    // stable line, 1.4.0 being alpha.
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.3.0")

    // Simple HTTP client for talking to the WLED JSON API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Persists the saved WLED controller address across app restarts
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Saved walls and routes. Room validates its queries at compile time,
    // which is most of why it is worth the annotation processor.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android SDK, but local unit tests run against a
    // stub android.jar whose org.json methods throw "not mocked". This pulls
    // in a real implementation so tests that parse JSON actually work.
    testImplementation("org.json:json:20231013")
    // Fake HTTP server so WledClient's request/response handling (headers,
    // status codes, timeouts) can be tested without a real WLED controller.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Lets tests substitute Dispatchers.Main, without which anything using
    // viewModelScope fails to run at all in a local JVM test. Version tracks
    // the kotlinx-coroutines-core that Compose/lifecycle resolve to.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Runs the DAOs against real SQLite on the JVM. Room validates its queries
    // at compile time, but that is not the same as running them - nothing there
    // checks that a cascade deletes, or that a Flow re-emits when a row changes.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.5")
}
