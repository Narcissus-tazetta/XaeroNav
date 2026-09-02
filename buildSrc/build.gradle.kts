plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
}

dependencies {
    // ノード名とノード別プロパティ（stonecutter.properties.toml）を規約プラグインから読むため
    implementation("dev.kikugie:stonecutter:0.9.7")
}
