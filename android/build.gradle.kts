plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
