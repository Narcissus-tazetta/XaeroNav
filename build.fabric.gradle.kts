plugins {
    id("xaeronav.common")
    id("fabric-loom") version "1.17.20"
}

stonecutter.properties.tags(stonecutter.current.version, "fabric")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

repositories {
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

loom {
    // 実行ディレクトリはノード配下（versions/<ノード>/run）のloom既定のまま。
    // ローダーごとにmodsの中身が違うので、NeoForge側のrun/と共有すると
    // 相手のローダー向けXaeroが混ざって読み込みに失敗する。
    runs {
        named("client") {
            client()
            configName = "Fabric Client (${stonecutter.current.project})"
        }
        // loomが既定で用意するserverの実行設定はこのMODでは使わない（クライアント専用MOD）。
        // runsコンテナから消してもloomが後から登録し直すので、名前が残るのは避けられない
    }
}

val xaeroModules = listOf(
    "xaero.lib:xaerolib-fabric-$minecraftVersion:${dep("xaerolib")}",
    "xaero.map:xaeroworldmap-fabric-$minecraftVersion:${dep("xaero_worldmap")}",
    "xaero.minimap:xaerominimap-fabric-$minecraftVersion:${dep("xaero_minimap")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
// このMODはXaero未導入でもワールド内描画だけで動く設計なので、その前提を実際に確かめる手段を残す。
val withXaero = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く。
val xaeroRuntimeMods: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${dep("fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${dep("fabric_api")}")

    // NeoForgeは本体に含んでいるが、Fabricには無いので同梱する（mixinの@Local / @WrapOperationが依存）
    implementation("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")
    include("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")

    // 設定のTOML読み書き。NeoForgeは本体が同じライブラリ(night-config)を含んでいるので、
    // 設定の定義はローダーによらず1箇所のままにできる。
    implementation("com.electronwill.night-config:core:${dep("night_config")}")
    implementation("com.electronwill.night-config:toml:${dep("night_config")}")
    include("com.electronwill.night-config:core:${dep("night_config")}")
    include("com.electronwill.night-config:toml:${dep("night_config")}")

    // Modsの一覧から設定画面を開けるようにするだけの連携。未導入でもエントリポイントが
    // 呼ばれなくなるだけなので、配布物にも実行時依存にも含めない。
    modCompileOnly("com.terraformersmc:modmenu:${dep("modmenu")}")
    modLocalRuntime("com.terraformersmc:modmenu:${dep("modmenu")}")

    // Xaeroはfabric.mod.json上optionalな連携先。コンパイルにだけ必要。
    // compileOnly（modの付かない方）だとMinecraftの型が中間マッピングのままで解決できない。
    xaeroModules.forEach { modCompileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Syncではなくコピーにして、手で入れた他のMODを消さない。
val installXaeroMods by tasks.registering(Copy::class) {
    from(xaeroRuntimeMods)
    into(layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める。
// Fabricで配るのは中間マッピングへ戻したremapJarの方で、素のjarではない
val stageRuntimeTestMods by tasks.registering(Copy::class) {
    from(xaeroRuntimeMods)
    from(tasks.named("remapJar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = modResourceProperties() + mapOf(
        "minecraft_version" to minecraftVersion,
        "fabric_loader_range" to dep("fabric_loader_range"),
        "xaero_worldmap_version" to dep("xaero_worldmap"),
        "xaero_minimap_version" to dep("xaero_minimap")
    )

    inputs.properties(replaceProperties)

    // NeoForge側のMOD定義はFabricのjarには要らない
    exclude("META-INF/neoforge.mods.toml")

    filesMatching("fabric.mod.json") {
        expand(replaceProperties)
    }
}

tasks.named("configureLaunch") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
