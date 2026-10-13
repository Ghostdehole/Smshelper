plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = providers.gradleProperty("appVersionName").orElse("2.0.1").get()
val versionParts = Regex("([0-9]{1,4})\\.([0-9]{1,3})\\.([0-9]{1,3})(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?")
    .matchEntire(appVersionName)
    ?: error("appVersionName must be a version such as 2.0.1 or 2.1.0-beta.1")
val defaultVersionCode = versionParts.groupValues[1].toLong() * 1_000_000 +
    versionParts.groupValues[2].toLong() * 1_000 + versionParts.groupValues[3].toLong()
val appVersionCode = providers.gradleProperty("appVersionCode")
    .orElse(defaultVersionCode.toString()).get().toIntOrNull()
    ?: error("appVersionCode must be a positive integer")

require(appVersionCode in 1..2_100_000_000) {
    "appVersionCode must be between 1 and 2100000000"
}
val previewBuildNumber = providers.gradleProperty("previewBuildNumber").orNull
require(previewBuildNumber == null || Regex("[1-9][0-9]{0,9}").matches(previewBuildNumber)) {
    "previewBuildNumber must be a positive integer"
}

android {
    namespace = "com.ghost.smshelper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ghost.smshelper"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview" + (previewBuildNumber?.let { ".$it" } ?: "")
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
