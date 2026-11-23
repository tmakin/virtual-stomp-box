plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Desktop replay of phone recordings: ./gradlew run --args="stomp-log.csv [key=value …]"
application { mainClass.set("uk.neatlines.stompbox.debug.ReplayKt") }
