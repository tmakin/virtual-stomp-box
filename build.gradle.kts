// Plugins go on the root classpath so the Kotlin and Android plugins share a classloader.
// The Android Gradle Plugin is only loaded when settings included :app (an SDK is present),
// so :core builds and tests without any Android tooling.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (rootProject.findProject(":app") != null) classpath("com.android.tools.build:gradle:8.7.3")
    }
}
