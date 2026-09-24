plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.kikugie.dev/releases") {
        name = "KikuGie"
        content { includeGroupByRegex("dev\\.kikugie(?:\\..*)?") }
    }
    maven("https://maven.kikugie.dev/snapshots") {
        name = "KikuGie Snapshots"
        content { includeGroupByRegex("dev\\.kikugie(?:\\..*)?") }
    }
}

dependencies {
    // ノード名とノード別プロパティ（stonecutter.properties.toml）を規約プラグインから読むため
    implementation("dev.kikugie:stonecutter:0.9.7")

    // Javaの@Mixinをコンパイル時に検出し、mixin configへ自動登録する。
    // まだalpha公開のみなので、再現可能性のため動的版ではなく検証済みの版へ固定する。
    implementation("dev.kikugie.fletching-table:fletching-table:0.2.0-alpha.9")

    // 1.16.5-forgeの開発実行で、Xaeroのクラスファイルに文字列で書かれたクラス名を書き換えるため（ForgeCoremodNames.kt）
    implementation("org.ow2.asm:asm:9.9.1")
}
