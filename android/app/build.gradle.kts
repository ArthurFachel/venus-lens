plugins {
    id("com.android.application")
}

// Build-time defaults come from the repo-root .env (gitignored; see .env.example).
// Only the server URL is baked in; the token is typed in Ajustes and stays on the phone.
val dotenv: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() }?.readLines()
    ?.map { it.trim().removePrefix("export ") }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim().trim('"', '\'') }
    ?: emptyMap()
val defaultUrl = System.getenv("VENUS_LENS_URL") ?: dotenv["VENUS_LENS_URL"] ?: ""

android {
    namespace = "dev.fachel.venuslens"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.fachel.venuslens"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_URL", "\"$defaultUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
