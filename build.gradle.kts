import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        phpstorm("2026.3")
        bundledPlugins("com.jetbrains.php", "org.jetbrains.plugins.yaml", "JavaScript", "com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("org.jetbrains.plugins.vue")
    }
}
