import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    if (versionFile.exists()) {
        load(versionFile.inputStream())
    }
}

fun String.asBuildConfigLiteral(): String =
    "\"" + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "") + "\""

fun prop(name: String, vararg aliases: String): String {
    val keys = sequenceOf(name, *aliases)
    val value = keys.mapNotNull { key ->
        localProperties.getProperty(key)?.takeIf(String::isNotBlank)
            ?: System.getenv(key)?.takeIf(String::isNotBlank)
    }.firstOrNull().orEmpty()
    return value.asBuildConfigLiteral()
}

/** Run a git command from the repo root; null on failure / empty output. */
fun runGit(vararg args: String): String? =
    try {
        val process = ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else null
    } catch (_: Exception) {
        null
    }

fun stripVersionPrefix(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V").trim()

/**
 * versionName resolution (first hit wins):
 * 1. VERSION_NAME env / local.properties (CI tag injection)
 * 2. Root version.properties (F-Droid UpdateCheck + release pin)
 * 3. Nearest GitHub-style tag via `git describe --match v*`
 * 4. Fallback for fresh checkouts without tags
 *
 * Examples: tag `v1.1.0` → `1.1.0`; 3 commits later → `1.1.0-3-gabc1234`
 */
fun resolveVersionName(): String {
    val override = localProperties.getProperty("VERSION_NAME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
    if (override != null) return stripVersionPrefix(override)

    versionProperties.getProperty("VERSION_NAME")?.takeIf { it.isNotBlank() }?.let {
        return stripVersionPrefix(it)
    }

    val describe = runGit("describe", "--tags", "--match", "v*", "--dirty")
        ?: runGit("describe", "--tags", "--dirty")
    if (describe != null) return stripVersionPrefix(describe)

    val shortSha = runGit("rev-parse", "--short", "HEAD")
    return if (shortSha != null) "0.1.0-$shortSha" else "0.1.0-dev"
}

/**
 * versionCode resolution (first hit wins):
 * 1. VERSION_CODE env / local.properties
 * 2. Root version.properties
 * 3. Monotonic `git rev-list --count HEAD`
 * 4. Encoded semver core from versionName
 */
fun resolveVersionCode(versionName: String): Int {
    val override = localProperties.getProperty("VERSION_CODE")?.toIntOrNull()?.takeIf { it > 0 }
        ?: System.getenv("VERSION_CODE")?.toIntOrNull()?.takeIf { it > 0 }
    if (override != null) return override

    versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()?.takeIf { it > 0 }?.let {
        return it
    }

    runGit("rev-list", "--count", "HEAD")?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }

    val core = versionName.substringBefore('-').substringBefore('+')
    val parts = core.split('.').mapNotNull { token ->
        token.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
    }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1)
}

val appVersionName: String = resolveVersionName()
val appVersionCode: Int = resolveVersionCode(appVersionName)
logger.lifecycle("TyphoonEye versionName=$appVersionName versionCode=$appVersionCode")

android {
    namespace = "seamain.org.typhoonEye"
    compileSdk = 36

    defaultConfig {
        applicationId = "seamain.org.typhoonEye"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject API keys from local.properties or System environment
        buildConfigField("String", "JUHE_KEY", prop("JUHE_KEY", "JUHE_API_KEY", "JUHEKEY"))
        buildConfigField("String", "QWEATHER_API_KEY", prop("QWEATHER_API_KEY"))
        buildConfigField("String", "QWEATHER_KID", prop("QWEATHER_KID", "QWEATHER_PUBLIC_ID"))
        buildConfigField("String", "QWEATHER_PROJECT_ID", prop("QWEATHER_PROJECT_ID"))
        buildConfigField(
            "String",
            "QWEATHER_PRIVATE_KEY",
            prop("QWEATHER_PRIVATE_KEY", "QWEATHER_PROJECT_KEY")
        )
        // Public QWeather API host by default. Dedicated console hosts override via local.properties.
        buildConfigField(
            "String",
            "QWEATHER_HOST",
            (localProperties.getProperty("QWEATHER_HOST")?.takeIf { it.isNotBlank() }
                ?: System.getenv("QWEATHER_HOST")?.takeIf { it.isNotBlank() }
                ?: "https://devapi.qweather.com/").asBuildConfigLiteral()
        )
        // Empty = bundled asset://map_style.json (Carto raster). Override if needed.
        buildConfigField(
            "String",
            "MAPLIBRE_STYLE_URL",
            (localProperties.getProperty("MAPLIBRE_STYLE_URL")?.takeIf { it.isNotBlank() }
                ?: "").asBuildConfigLiteral()
        )
        // Amap (高德) Web Key — used for mainland China basemap tiles. Create at https://console.amap.com/dev/key/app
        // Key type: 「Web服务」or 「Web端(JS API)」 depending on console product; tile auth is best-effort.
        buildConfigField(
            "String",
            "AMAP_KEY",
            (localProperties.getProperty("AMAP_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("AMAP_KEY")?.takeIf { it.isNotBlank() }
                ?: "").asBuildConfigLiteral()
        )
        // Optional basemap force: auto | amap | open
        buildConfigField(
            "String",
            "MAP_BASEMAP",
            (localProperties.getProperty("MAP_BASEMAP")?.takeIf { it.isNotBlank() }
                ?: System.getenv("MAP_BASEMAP")?.takeIf { it.isNotBlank() }
                ?: "auto").asBuildConfigLiteral()
        )
        // In-app updates via GitHub Releases API
        buildConfigField(
            "String",
            "GITHUB_OWNER",
            (localProperties.getProperty("GITHUB_OWNER")?.takeIf { it.isNotBlank() }
                ?: System.getenv("GITHUB_OWNER")?.takeIf { it.isNotBlank() }
                ?: "Seamain").asBuildConfigLiteral()
        )
        buildConfigField(
            "String",
            "GITHUB_REPO",
            (localProperties.getProperty("GITHUB_REPO")?.takeIf { it.isNotBlank() }
                ?: System.getenv("GITHUB_REPO")?.takeIf { it.isNotBlank() }
                ?: "TyphoonEyeAndroid").asBuildConfigLiteral()
        )
        }

    // github (default) keeps R.bool.enable_in_app_updates=true from main.
    // fdroid overrides it to false via src/fdroid/res/values/distribution.xml
    // and strips REQUEST_INSTALL_PACKAGES via src/fdroid/AndroidManifest.xml.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
            val keystoreFile = storeFilePath?.let { rootProject.file(it) } ?: rootProject.file("release.keystore")

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Git checkout shape differs between GitHub Actions and F-Droid;
            // embedding VCS info makes AndroidManifest/APK unreproducible.
            vcsInfo {
                include = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // Per-ABI APKs: each APK ships only one architecture's native libs.
    // Drops universal APK (~53 MB) to ~7 MB per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Compress native .so files inside the APK (legacy behaviour).
        // Default AGP 8+ stores them PAGE-aligned & uncompressed for mmap,
        // but that inflates the APK. Legacy packaging yields ~7 MB per ABI.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/version-control-info.textproto"
        }
    }

    // AGP 8+ embeds a "Dependency metadata" APK signing block for Play.
    // F-Droid's check-apk scanner rejects that extra block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// F-Droid-recommended low-bit ABI encoding: versionCode = base * 10 + abiSuffix.
// Matches metadata VercodeOperation (10*%c+1..4): armeabi-v7a < arm64-v8a < x86 < x86_64.
val abiVersionCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)

android.applicationVariants.configureEach {
    outputs.configureEach {
        val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
        val abi = output.getFilter("ABI")
        if (abi != null) {
            output.versionCodeOverride =
                appVersionCode * 10 + (abiVersionCodes[abi] ?: 0)
        }
    }
}

// AGP 8+ always merges ART baseline profiles from androidx AARs.
// Disable those tasks so F-Droid and GitHub APKs both omit baseline.prof.
tasks.configureEach {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.eddsa)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    // Activity-scoped ViewModels: default viewModel() + @AndroidEntryPoint is enough.
    // (hilt-navigation-compose only needed for per-backStackEntry hiltViewModel().)
    implementation(libs.hilt.work)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20231013") // For testing JSON in unit tests
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.material3)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
