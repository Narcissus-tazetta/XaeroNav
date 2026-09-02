pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "xaeronav"

// ノード名は `<MCバージョン>-<ローダー>`。ビルドスクリプトはローダーごとに1本で、
// MCバージョンを増やしてもここへ1行足すだけで済む（依存バージョンはstonecutter.properties.tomlへ）。
stonecutter {
    create(rootProject) {
        fun match(minecraft: String, vararg loaders: String) = loaders.forEach {
            version("$minecraft-$it", minecraft).buildscript("build.$it.gradle.kts")
        }

        match("1.21.1", "neoforge", "fabric")

        // gitへコミットする状態。Stonecutterはsrc/を書き換えるので、
        // ここと違うノードを有効にしたまま差分を取ると全ファイルが動いて見える。
        vcsVersion.set("1.21.1-neoforge")
    }
}
