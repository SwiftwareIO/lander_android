import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.acme.shop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acme.shop"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Seeded local test key from supabase/seed.sql. Publishable keys are safe
        // to ship in apps. Override with -PlanderApiKey=pk_live_...
        val apiKey = providers.gradleProperty("landerApiKey").orElse("pk_test_DemoPublishableKey000000000000")
        // The local API from `pnpm dev`; the emulator reaches the host at 10.0.2.2.
        // Override with -PlanderBaseUrl=https://api.golander.link
        val baseUrl = providers.gradleProperty("landerBaseUrl").orElse("http://10.0.2.2:8787")
        buildConfigField("String", "LANDER_API_KEY", "\"${apiKey.get()}\"")
        buildConfigField("String", "LANDER_BASE_URL", "\"${baseUrl.get()}\"")
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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":lander"))
    implementation(libs.androidx.activity.ktx)
}
