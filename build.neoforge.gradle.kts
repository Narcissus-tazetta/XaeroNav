plugins {
    id("xaeronav.common")
    id("net.neoforged.moddev") version "2.0.144"
}

stonecutter.properties.tags(stonecutter.current.version, "neoforge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

neoForge {
    version = dep("neoforge")

    mods {
        create(modProperty("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    // 単体テストからMinecraftの素の値型（BlockPos・Vec3・Mth）を使えるようにする。
    // これが無いとtestCompileClasspathにMinecraftが載らず、経路探索コアのテストは
    // 1行も書けない（既存テストがHeuristic等のMinecraft非依存クラスに限られていたのはこのため）。
    // なお、ここで載るのはクラスパスだけで、Blocks/BuiltInRegistriesに触るにはBootstrapが要る。
    // テストはレジストリを起動しなくても動く範囲に留めること。
    addModdingDependenciesTo(sourceSets["test"])

    // クライアント専用MOD（@Mod(dist = Dist.CLIENT)）なので、専用サーバーの実行設定は用意しない
    runs {
        create("client") {
            client()
            gameDirectory = rootProject.layout.projectDirectory.dir("run")
        }
    }
}

// artifactIdは "-forge-" ではなく "-neoforge-"。chocolateminecraft.comのmavenには両方存在し、
// "-forge-"版はNeoForge実行時に「Forge用/古いNeoForge用のため読み込めません」で無視される。
val xaeroModules = listOf(
    "xaero.lib:xaerolib-neoforge-$minecraftVersion:${dep("xaerolib")}",
    "xaero.map:xaeroworldmap-neoforge-$minecraftVersion:${dep("xaero_worldmap")}",
    "xaero.minimap:xaerominimap-neoforge-$minecraftVersion:${dep("xaero_minimap")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
// このMODはXaero未導入でもワールド内描画だけで動く設計なので、その前提を実際に確かめる手段を残す
// （xaeronav-xaero.mixins.jsonはrequired=falseなので、Xaeroが無ければ地図連携だけが黙って無効になる）。
val withXaero = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く。
// additionalRuntimeClasspathに載せるとクラスパスには現れるがFMLがMODとして検出せず、
// Xaeroのクラスだけが「Minecraftのクラスを解決できないレイヤー」に置かれる。すると
// ModList上は未導入なのにClass.forNameは成功するという食い違いが生まれ、触った瞬間に
// NoClassDefFoundErrorでゲームごと落ちる。
val xaeroRuntimeMods: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    // Xaeroはmods.toml上optionalな連携先。コンパイルにだけ必要で、配布物にも実行時依存にも含めない。
    // implementationにするとruntimeClasspathへ載り、「Xaeroが無くても動く」が一度も検証されないまま
    // 開発が進んでしまう。
    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Syncではなくコピーにして、手で入れた他のMODを消さない。バージョンを上げたときに古いjarが
// 残るが、mods以下を消して入れ直せば済む。
val installXaeroMods by tasks.registering(Copy::class) {
    from(xaeroRuntimeMods)
    into(rootProject.layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods, "prepareClientRun")
}

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める
val stageRuntimeTestMods by tasks.registering(Copy::class) {
    from(xaeroRuntimeMods)
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = modResourceProperties() + mapOf(
        "minecraft_version" to minecraftVersion,
        "neoforge_loader_version_range" to dep("neoforge_loader_range"),
        "xaero_worldmap_version" to dep("xaero_worldmap"),
        "xaero_minimap_version" to dep("xaero_minimap")
    )

    inputs.properties(replaceProperties)

    // Fabric側のMOD定義はNeoForgeのjarには要らない
    exclude("fabric.mod.json")

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

tasks.named("createMinecraftArtifacts") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
