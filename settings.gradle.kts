dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "virtual-stomp-box"

// core: plain-JVM detector code, tests and the replay tool. Builds anywhere.
include(":core")
// app: the Android test app. Needs an Android SDK (set ANDROID_HOME or sdk.dir in local.properties).
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()) {
    include(":app")
}
