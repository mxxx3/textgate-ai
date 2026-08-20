// Root build file. Intentionally empty of dependencies beyond plugin declarations:
// all real configuration lives in app/build.gradle.kts. Keeping this minimal
// makes it trivial to audit — there is nothing here that could add a hidden
// dependency or repository.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
