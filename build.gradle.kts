// Top-level build file. The plugin version is declared once here and applied in :app.
//
// There is no Kotlin plugin here on purpose: AGP 9 compiles Kotlin itself, and applying
// org.jetbrains.kotlin.android alongside it fails with AgpWithBuiltInKotlinAppliedCheck.
plugins {
    id("com.android.application") version "9.3.2" apply false
}
