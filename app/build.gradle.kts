import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Environment variables take precedence over the ignored local signing file.
// CI supplies a complete environment and never reads local credentials.
val releaseSigningEnvironment = mapOf(
    "storeFile" to "LIGHTROVE_KEYSTORE_PATH",
    "storePassword" to "LIGHTROVE_KEYSTORE_PASSWORD",
    "keyAlias" to "LIGHTROVE_KEY_ALIAS",
    "keyPassword" to "LIGHTROVE_KEY_PASSWORD",
)
val useSigningEnvironment = releaseSigningEnvironment.values.any { providers.environmentVariable(it).isPresent }
val localSigningProperties = Properties().apply {
    if (!useSigningEnvironment) {
        val propertiesFile = rootProject.file(".signing/release.properties")
        if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningValues = releaseSigningEnvironment.mapValues { (property, environment) ->
    if (useSigningEnvironment) providers.environmentVariable(environment).orNull
    else localSigningProperties.getProperty(property)
}
val missingSigningValues = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
val releaseKeystore = releaseSigningValues["storeFile"]?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").map(String::toBoolean).getOrElse(false)
if (requireReleaseSigning) {
    check(missingSigningValues.isEmpty()) { "Release signing is incomplete. Configure .signing/release.properties or all LIGHTROVE signing environment variables." }
    check(releaseKeystore?.isFile == true) { "Release keystore does not exist." }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Prevents unsigned release builds."
    doLast {
        check(missingSigningValues.isEmpty()) { "Release signing is incomplete. Restore the private signing files or configure the LIGHTROVE signing environment variables; see docs/RELEASING.md." }
        check(releaseKeystore?.isFile == true) { "Release keystore does not exist." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

android {
    namespace = "com.mcgeelee.photos"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.mcgeelee.photos"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (missingSigningValues.isEmpty()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources {
        localeFilters += listOf("en", "zh-rCN", "zh-rTW", "ja", "ko", "hi")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.tasks)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.chinese)
    implementation(libs.mlkit.japanese)
    implementation(libs.mlkit.korean)
    implementation(libs.mlkit.devanagari)
    implementation(libs.litert.lm)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
