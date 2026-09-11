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
            gameDirectory = rootProject.layout.projectDirectory.dir("run")
        }
    }

    // 単体テストからMinecraftの素の値型（BlockPos等）を使えるようにする
    // （NeoForgeノードと同じ理由。night-configもForge本体が同梱しているのでここで載る）
    addModdingDependenciesTo(sourceSets["test"])
}

val xaeroModules = listOf(
    "xaero.lib:xaerolib-forge-$mcVersion:${dep("xaerolib")}",
    "xaero.map:xaeroworldmap-forge-$mcVersion:${dep("xaero_worldmap")}",
    "xaero.minimap:xaerominimap-forge-$mcVersion:${dep("xaero_minimap")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

val xaeroRuntimeMods: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }

    // compileOnlyだけにする（annotationProcessorに足すとMixin APが公式マッピングランタイムで
    // ビルドを止める。1.21.1-forgeで踏んだ罠と同じ）
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

val installXaeroMods by tasks.registering(Copy::class) {
    from(xaeroRuntimeMods)
    into(rootProject.layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

val stageRuntimeTestMods by tasks.registering(Copy::class) {
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
