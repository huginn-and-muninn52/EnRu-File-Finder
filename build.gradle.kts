plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.github.huginnandmuninn52"
version = "1.4.7"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        // VCS module is needed to open diffs
        bundledModule("com.intellij.modules.vcs")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Make the plugin weight 10 KB instead of 1.7 MB
    compileOnly(kotlin("stdlib"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }
    
    // For verifyPlugin gradle task
    pluginVerification {
        ides {
            create("IU", "2025.2.4")
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}