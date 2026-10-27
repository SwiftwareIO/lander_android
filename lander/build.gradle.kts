import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = property("GROUP").toString()
version = property("VERSION_NAME").toString()

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
        warningsAsErrors = true
        // Version freshness checks need the network and are not about this code.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency")
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
    // api: the public API exposes Kotlin and coroutines types (Flow, suspend functions).
    api(libs.kotlin.stdlib)
    api(libs.kotlinx.coroutines.android)
    // Play Install Referrer: read once on first launch for deferred deep links.
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar only has stubs of org.json; unit tests need the real thing.
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}

// Maven Central through the Central Portal. See RELEASING.md. Credentials come from
// Gradle properties or ORG_GRADLE_PROJECT_ environment variables: mavenCentralUsername,
// mavenCentralPassword, signingInMemoryKey and signingInMemoryKeyPassword.
mavenPublishing {
    coordinates(group.toString(), property("POM_ARTIFACT_ID").toString(), version.toString())
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    // Sign only when a key is configured, so publishToMavenLocal works without one.
    // Maven Central rejects unsigned artifacts, so a release without a key fails there.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("Lander Android SDK")
        description.set("Deep linking without the fuss: receive, resolve and create Lander links in Android apps.")
        inceptionYear.set("2026")
        url.set("https://golander.link")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("swiftwareio")
                name.set("Lander")
                organization.set("Swiftware")
                organizationUrl.set("https://golander.link")
                url.set("https://github.com/SwiftwareIO")
            }
        }
        scm {
            url.set("https://github.com/SwiftwareIO/lander_android")
            connection.set("scm:git:https://github.com/SwiftwareIO/lander_android.git")
            developerConnection.set("scm:git:ssh://git@github.com/SwiftwareIO/lander_android.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/SwiftwareIO/lander_android/issues")
        }
    }
}
