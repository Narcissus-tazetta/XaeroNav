plugins {
    id("xaeronav.common")
    // 26.1以降は難読化されていないので、リマップをしないLoom（旧来のfabric-loomはマッピングを要る）
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

stonecutter.properties.tags(stonecutter.current.version, "fabric")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

// fabric.mod.jsonの"java"依存へ渡す実行時要件
val javaVersion = javaVersionFor(minecraftVersion)
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

repositories {
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

// 開放しているのはRenderType.CompositeState等（NavRenderTypes）で、26.1にはその構造が無い。
// fabric.mod.jsonの"accessWidener"は常にこの名前で指すので、見出しだけの空のファイルを置く
val emptyAccessWidener: File = layout.buildDirectory.file("generated/emptyAccessWidener/xaeronav.accesswidener").get().asFile.also {
    it.parentFile.mkdirs()
    it.writeText("accessWidener v2 official\n")
}

loom {
    accessWidenerPath = emptyAccessWidener

    // 実行ディレクトリはノード配下（versions/<ノード>/run）のloom既定のまま。
    runs {
        named("client") {
            client()
            configName = "Fabric Client (${stonecutter.current.project})"
            // `-Pxaeronav.quickPlay=<ワールド名>`でタイトル画面を飛ばして既存のワールドへ入る（手元の確認用）
            providers.gradleProperty("xaeronav.quickPlay").orNull?.let { programArgs("--quickPlaySingleplayer", it) }
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { vmArg(it) }
        }
    }
}

val xaeroModules = xaeroModuleCoordinates(
    "fabric", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = withXaeroProperty()

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く。
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")

    implementation("net.fabricmc:fabric-loader:${dep("fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${dep("fabric_api")}")

    // Fabricには本体にMixinExtrasが無いので同梱する（mixinの@Local / @WrapOperationが依存）
    implementation("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")
    include("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")

    // 設定のTOML読み書き（NightConfigStore）
    implementation("com.electronwill.night-config:core:${dep("night_config")}")
    implementation("com.electronwill.night-config:toml:${dep("night_config")}")
    include("com.electronwill.night-config:core:${dep("night_config")}")
    include("com.electronwill.night-config:toml:${dep("night_config")}")

    // Modsの一覧から設定画面を開けるようにするだけの連携。未導入でもエントリポイントが
    // 呼ばれなくなるだけなので、配布物にも実行時依存にも含めない
    compileOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        exclude(group = "eu.pb4", module = "placeholder-api")
    }
    runtimeOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        exclude(group = "eu.pb4", module = "placeholder-api")
    }

    // Xaeroはfabric.mod.json上optionalな連携先。コンパイルにだけ必要
    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Syncではなくコピーにして、手で入れた他のMODを消さない。
val installXaeroMods = tasks.register<Copy>("installXaeroMods") {
    from(xaeroRuntimeMods)
    into(layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "fabric_loader_range" to dep("fabric_loader_range"),
        "fabric_api_range" to dep("fabric_api_range"),
        "fabric_api_mod_id" to fabricApiModIdFor(minecraftVersion),
        "java_version" to javaVersion.toString()
    )

    inputs.properties(replaceProperties)

    // NeoForge/Forge側のMOD定義・AT定義はFabricのjarには要らない
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/mods.toml")
    exclude("META-INF/accesstransformer.cfg")

    // 開放する行を落として見出しだけにする（emptyAccessWidenerと同じ中身）
    filesMatching("xaeronav.accesswidener") {
        filter { line -> if (line.startsWith("accessWidener ")) "accessWidener v2 official" else "" }
    }

    filesMatching("fabric.mod.json") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}

tasks.named("configureLaunch") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
