plugins {
    id("xaeronav.common")
    // jar-in-jar（jarJar configuration）はlegacyforgeプラグイン自体が既に持っている。
    // FG6/7向けのnet.minecraftforge.jarjarを別途当てると衝突する（"jarJar configuration already exists"）
    id("net.neoforged.moddev.legacyforge") version "2.0.146"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val mcVersion = dep("minecraft")
// xaeronav.common.gradle.ktsのtoolchain分岐と同じ境界線。1.17〜1.20.1専用のノードなので今のところ常にJava 17
val mixinCompatibilityLevel = if (mcVersion.startsWith("1.20.")) "JAVA_17" else "JAVA_21"
val packFormat = if (mcVersion.startsWith("1.20.")) 15 else 34

legacyForge {
    // enable{}の中でmods/runs等（外側の拡張のメンバー）に触ると、enable()自身がまだ
    // 「有効化」を終える前に評価されて"Mod development has not been enabled yet"で落ちる。
    // enable{}にはバージョン指定だけを書き、mods/runsはenable()の呼び出しが完了した後に書く
    enable {
        forgeVersion = "$mcVersion-${dep("forge")}"
    }

    mods {
        create(modProperty("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    // クライアント専用MOD。専用サーバーの実行設定は用意しない
    runs {
        create("client") {
            client()
            // 全ノードでroot/runを共有すると、別MC版・別ローダーのXaero jarまで同時に
            // 読み込まれてMixinが異なるMinecraftへ適用される。ノードごとに完全分離する。
            gameDirectory = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.project}")
        }
    }

    // 単体テストからMinecraftの素の値型（BlockPos等）を使えるようにする
    // （NeoForgeノードと同じ理由。night-configもForge本体が同梱しているのでここで載る）
    addModdingDependenciesTo(sourceSets["test"])
}

// Forge 1.20.1のFMLはmods.tomlの[[mixins]]を読まず、MANIFESTのMixinConfigsだけを見る。
// また本番はSRG名で動くので、注入先の文字列（render・endBatch等）をSRGへ引くrefmapが要る。
// どちらが欠けてもXaero連携のmixinは本番で1本も当たらない
mixin {
    add(sourceSets["main"], "${modProperty("mod_id")}.refmap.json")
    config("${modProperty("mod_id")}-xaero.mixins.json")
}

// mixin.config()が効くのは開発実行の引数だけで、配布jarのMANIFESTには書かれない
tasks.named<Jar>("jar") {
    manifest.attributes("MixinConfigs" to "${modProperty("mod_id")}-xaero.mixins.json")
}

val xaeroModules = listOf(
    "xaero.lib:xaerolib-forge-$mcVersion:${dep("xaerolib")}",
    "xaero.map:xaeroworldmap-forge-$mcVersion:${dep("xaero_worldmap")}",
    "xaero.minimap:xaerominimap-forge-$mcVersion:${dep("xaero_minimap")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

val xaeroRuntimeMods: Configuration = configurations.create("xaeroRuntimeMods") {
    isTransitive = false
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    // 公開jarはSRG名前空間なので、通常のcompileOnly/runtimeコピーではnamed開発環境で
    // Xaero自身のMixin（@Shadow f_...）が失敗する。MDGのmod構成でnamedへリマップする。
    xaeroModules.forEach { modCompileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { modRuntimeOnly(it) }
        // stageRuntimeTestModsには配布時と同じ未変換jarを渡す。
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }

    // @WrapOperation・@ModifyReturnValueはMixin本体のAPが知らない注入なので、mixinextras-commonを
    // APにも載せないとrefmapへ載らない。1.21.1-forgeでAPに載せてビルドが止まったのは公式マッピングで
    // 「マッピング無し」になるためで、SRGを渡すこのノードでは起きない
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = modResourceProperties() + mapOf(
        "minecraft_version" to mcVersion,
        "forge_loader_version_range" to dep("forge_loader_range"),
        "xaero_worldmap_version" to dep("xaero_worldmap"),
        "xaero_minimap_version" to dep("xaero_minimap"),
        "mixin_compatibility_level" to mixinCompatibilityLevel,
        "pack_format" to packFormat.toString()
    )

    inputs.properties(replaceProperties)

    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")

    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}

// NeoForgeノードと同じ理由（ModDevGradle系のcreateMinecraftArtifactsが暗黙の依存を持つ）
tasks.named("createMinecraftArtifacts") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
