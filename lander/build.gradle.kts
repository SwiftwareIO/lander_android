import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "link.lander"
version = "0.1.0"

android {
    namespace = "link.lander.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // Reported as `sdkVersion` and in the X-Lander-Sdk header.
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Apps on older Kotlin versions can still read this library's metadata.
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // Play Install Referrer: read once on first launch for deferred deep links.
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar only has stubs of org.json; unit tests need the real thing.
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "link.lander"
            artifactId = "lander-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Lander Android SDK")
                description.set("Deep linking without the fuss: receive, resolve and create Lander links in Android apps.")
                url.set("https://golander.link")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("lander")
                        name.set("Lander")
                        url.set("https://golander.link")
                    }
                }
                scm {
                    url.set("https://github.com/SwiftwareIO/lander")
                }
            }
        }
    }
    // No remote repository here: `./gradlew :lander:publishToMavenLocal` for local
    // testing. Add Maven Central credentials in CI when publishing is set up.
}
