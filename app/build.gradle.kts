import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildTimeValue: String = (findProperty("BUILD_TIME") as String?)
    ?: Instant.now().toString()

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

android {
    namespace = "com.hulk.pillsapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hulk.pillsapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4-t04-fix1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"$buildTimeValue\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
}
