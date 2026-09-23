plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "xin.dponnood.remoteservice.core.network"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    // Android's compile-time org.json stubs throw in local JVM tests. Keep a
    // real parser implementation on the test classpath so API fixtures are
    // exercised without an emulator or a router.
    testImplementation("org.json:json:20240303")
}
