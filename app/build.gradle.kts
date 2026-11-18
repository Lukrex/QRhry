import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val catalogProperties = Properties().apply {
    val file = rootProject.file("catalog.properties")
    if (file.isFile) file.inputStream().use { stream -> load(stream) }
}

android {
    namespace = "com.qrhry.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qrhry.app"
        minSdk = 23
        targetSdk = 34
        resourceConfigurations += listOf("en", "sk")
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.3")

    // Keep Firebase's Kotlin metadata compatible with the project's Kotlin 1.9.22 toolchain.
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android.defaultConfig.buildConfigField(
    "boolean",
    "CATALOG_CONFIGURED",
    listOf("firebase.apiKey", "firebase.appId", "firebase.projectId", "firebase.storageBucket")
        .all { catalogProperties.getProperty(it).orEmpty().isNotBlank() }.toString()
)
listOf(
    "FIREBASE_API_KEY" to "firebase.apiKey",
    "FIREBASE_APP_ID" to "firebase.appId",
    "FIREBASE_PROJECT_ID" to "firebase.projectId",
    "FIREBASE_STORAGE_BUCKET" to "firebase.storageBucket"
).forEach { (field, property) ->
    val value = catalogProperties.getProperty(property).orEmpty()
    android.defaultConfig.buildConfigField("String", field, "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
}
val catalogEmulatorHost = catalogProperties.getProperty("firebase.emulatorHost").orEmpty()
android.defaultConfig.buildConfigField(
    "String",
    "FIREBASE_EMULATOR_HOST",
    "\"${catalogEmulatorHost.replace("\\", "\\\\").replace("\"", "\\\"")}\""
)
