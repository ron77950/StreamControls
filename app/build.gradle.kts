import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugClientId = localSettings.getProperty("streamcontrol.debugClientId", localSettings.getProperty("streamcontrol.browserClientId", ""))
val releaseClientId = localSettings.getProperty("streamcontrol.releaseClientId", "")
listOf(debugClientId, releaseClientId).forEach { require(it.isEmpty() || it.matches(Regex("[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com"))) }

android {
    namespace = "com.streamcontrols.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.streamcontrols.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        resValue("string", "twitch_client_id", "evgpyx9cjl9zsbg0g9hoqf22ijbmnu")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug { resValue("string", "browser_oauth_client_id", debugClientId) }
        release {
            resValue("string", "browser_oauth_client_id", releaseClientId)
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    testOptions {
        // Local JVM tests exercise debug diagnostics without an Android log service.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.browser:browser:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
