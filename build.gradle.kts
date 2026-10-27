plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin compiler version.
    alias(libs.plugins.kotlin.android) apply false
}
