import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val versionProperties = Properties().apply {
    val file = rootProject.file("gradle/version.properties")
    require(file.isFile) { "Missing ${file.path}; release version is not defined" }
    file.inputStream().use(::load)
}

fun versionProperty(name: String): String = versionProperties.getProperty(name)?.trim().orEmpty().also {
    require(it.isNotEmpty()) { "Missing $name in gradle/version.properties" }
}

val appVersionCode = versionProperty("versionCode").toIntOrNull()
    ?.also { require(it > 0) { "versionCode must be positive" } }
    ?: error("versionCode must be an integer")
val appVersionName = versionProperty("versionName")

val localSigningProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingProperty(propertyName: String, environmentName: String): String? =
    providers.environmentVariable(environmentName).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: localSigningProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingProperty("storeFile", "REMOTE_SERVICES_KEYSTORE")
val releaseStorePassword = signingProperty("storePassword", "REMOTE_SERVICES_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("keyAlias", "REMOTE_SERVICES_KEY_ALIAS")
val releaseKeyPassword = signingProperty("keyPassword", "REMOTE_SERVICES_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "xin.dponnood.remoteservice"
    compileSdk = 36

    defaultConfig {
        applicationId = "xin.dponnood.remoteservice"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Keep emulator/debug installs isolated from a user's signed beta APK
        // so local UI checks cannot replace their configured services.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "远程服务（测试）")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile!!)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
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
        compose = true
    }
}

val explicitReleasePackagingRequested = gradle.startParameter.taskNames.any { taskName ->
    when (taskName.substringAfterLast(':')) {
        "assembleRelease", "bundleRelease", "packageRelease" -> true
        else -> false
    }
}

fun requireReleaseSigning() {
    check(releaseSigningConfigured) {
        "Release signing is not configured. Set REMOTE_SERVICES_KEYSTORE, " +
            "REMOTE_SERVICES_KEYSTORE_PASSWORD, REMOTE_SERVICES_KEY_ALIAS and " +
            "REMOTE_SERVICES_KEY_PASSWORD (or use ignored keystore.properties)."
    }
}

// Fail early when a release packaging task was explicitly requested. The
// conditional is important: AGP configures release variants while running
// debug/test tasks too, and those tasks must remain usable without a release
// keystore. The package/bundle guards below cover aggregate tasks such as
// `build`, where the requested task name itself is not assembleRelease.
androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        if (explicitReleasePackagingRequested) requireReleaseSigning()
    }
}

// A doFirst on assembleRelease is too late because packageRelease is its
// dependency. These guards execute before the package/bundle actions and stop
// aggregate invocations before an unsigned release artifact can be emitted.
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst { requireReleaseSigning() }
    }
}

dependencies {
    implementation(project(":adapter:luci"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:update"))
    implementation(project(":core:logging"))
    implementation(project(":feature:services"))
    implementation(project(":feature:update"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:web"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
