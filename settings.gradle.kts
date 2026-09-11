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
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "xaeronav"

// ノード名は `<MCバージョン>-<ローダー>`。ビルドスクリプトはローダーごとに1本で、
// MCバージョンを増やしてもここへ1行足すだけで済む（依存バージョンはstonecutter.properties.tomlへ）。
stonecutter {
    create(rootProject) {
        fun match(minecraft: String, vararg loaders: String) = loaders.forEach {
            version("$minecraft-$it", minecraft).buildscript("build.$it.gradle.kts")
        }

        match("1.21.1", "neoforge", "fabric", "forge")
        match("1.20.1", "fabric")
        // 1.20.1はForgeGradle 7ではなくModDevGradleのlegacyforgeプラグインを使う
        // （1.17〜1.20.1向け、上流もこちらへの移行を推奨）ので専用のビルドスクリプトを充てる。
        // NeoForge 1.20.1は見送り——その版のNeoForgeはForgeとjarレベルで互換で
        // （NeoForge自身も1.20.1ではForgeの使用を推奨）、Xaero側も"neoforge"向けの
        // 1.20.1ビルドを配っていない（1.20.4からしか無い）
        version("1.20.1-forge", "1.20.1").buildscript("build.forge-legacy.gradle.kts")

        // gitへコミットする状態。Stonecutterはsrc/を書き換えるので、
        // ここと違うノードを有効にしたまま差分を取ると全ファイルが動いて見える。
        vcsVersion.set("1.21.1-neoforge")
    }
}
