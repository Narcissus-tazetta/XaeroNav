pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "xaeronav"

// gitへコミットする状態。Stonecutterはsrc/を書き換えるので、
// ここと違うノードを有効にしたまま差分を取ると全ファイルが動いて見える。
val vcsNode = "1.21.1-neoforge"

// `-Pxaeronav.onlyNodes=<ノード>[,<ノード>...]`で、そのノード（と正典ノード）だけを構成する。
// CIの各ジョブは1ノードしか使わないのに、全ノードを構成すると全ローダーのmaven・Minecraftの取得を
// 毎ジョブ踏み、どれか1つの一時的な不調でジョブが落ちる。正典ノードはStonecutterの有効ノードなので外せない
val onlyNodes = providers.gradleProperty("xaeronav.onlyNodes").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
// onlyNodesで絞っても全ノードを`printNodes`で出せるように、構成しないノードも`<ノード>|<MCバージョン>`で残す
val allNodes = mutableListOf<String>()

// ノード名は `<MCバージョン>-<ローダー>`。ビルドスクリプトはローダーごとに1本で、
// MCバージョンを増やしてもここへ1行足すだけで済む（依存バージョンはstonecutter.properties.tomlへ）。
stonecutter {
    create(rootProject) {
        fun node(project: String, minecraft: String, buildscript: String) {
            allNodes += "$project|$minecraft"
            if (onlyNodes == null || project in onlyNodes || project == vcsNode) {
                version(project, minecraft).buildscript(buildscript)
            }
        }
        fun match(minecraft: String, vararg loaders: String) = loaders.forEach {
            node("$minecraft-$it", minecraft, "build.$it.gradle.kts")
        }

        // NeoForge 26.3は安定版（betaでない版）が出るまで足さない
        match("26.3", "forge")
        node("26.3-fabric", "26.3", "build.fabric-26.gradle.kts")
        match("26.2", "neoforge", "forge")
        node("26.2-fabric", "26.2", "build.fabric-26.gradle.kts")
        match("26.1.2", "neoforge", "forge")
        node("26.1.2-fabric", "26.1.2", "build.fabric-26.gradle.kts")
        match("1.21.11", "neoforge", "fabric", "forge")
        match("1.21.10", "neoforge", "fabric", "forge")
        match("1.21.8", "neoforge", "fabric", "forge")
        match("1.21.5", "neoforge", "fabric", "forge")
        match("1.21.4", "neoforge", "fabric", "forge")
        match("1.21.3", "neoforge", "fabric", "forge")
        match("1.21.1", "neoforge", "fabric", "forge")
        match("1.20.6", "neoforge", "fabric", "forge")
        match("1.20.4", "neoforge", "fabric", "forge")
        // NeoForge 20.2はbetaしか無い
        match("1.20.2", "fabric", "forge")
        match("1.20.1", "fabric")
        match("1.19.2", "fabric")
        match("1.18.2", "fabric")
        // 1.16.5のForgeはForgeGradleもModDevGradleも公式マッピングで扱えないので、Architectury Loomの専用スクリプトを充てる
        match("1.16.5", "fabric")
        node("1.16.5-forge", "1.16.5", "build.forge-116.gradle.kts")
        // 1.20.1はForgeGradle 7ではなくModDevGradleのlegacyforgeプラグインを使う
        // （1.17〜1.20.1向け、上流もこちらへの移行を推奨）ので専用のビルドスクリプトを充てる。
        // NeoForge 1.20.1は見送り——その版のNeoForgeはForgeとjarレベルで互換で
        // （NeoForge自身も1.20.1ではForgeの使用を推奨）、Xaero側も"neoforge"向けの
        // 1.20.1ビルドを配っていない（1.20.4からしか無い）
        node("1.20.1-forge", "1.20.1", "build.forge-legacy.gradle.kts")
        // 1.18.2・1.19.2のForgeも同じlegacyforgeプラグイン（1.17〜1.20.1向け）で作る
        node("1.19.2-forge", "1.19.2", "build.forge-legacy.gradle.kts")
        node("1.18.2-forge", "1.18.2", "build.forge-legacy.gradle.kts")

        vcsVersion.set(vcsNode)
    }
}

gradle.extensions.extraProperties.set("xaeronav.allNodes", allNodes.toList())

gradle.beforeProject {
    if (name == "1.16.5-forge") {
        extensions.extraProperties.set("loom.platform", "forge")
    }
}
