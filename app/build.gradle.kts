plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.goyo.wazak"
    compileSdk = 36

    val env = rootProject.file("../.env")
        .takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@mapNotNull null
            val key = trimmed.substringBefore("=").trim()
            val value = trimmed.substringAfter("=").trim().trim('"')
            key to value
        }
        ?.toMap()
        ?: emptyMap()

    defaultConfig {
        applicationId = "ai.goyo.wazak"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${env["SUPABASE_URL"].orEmpty()}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${env["SUPABASE_PUBLISHABLE_KEY"] ?: env["SUPABASE_ANON_KEY"].orEmpty()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
