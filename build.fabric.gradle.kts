import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("xaeronav.common")
    id("fabric-loom") version "1.17.20"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1" apply false
}

stonecutter.properties.tags(stonecutter.current.version, "fabric")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")
if (minecraftVersion.startsWith("1.16.")) {
    pluginManager.apply("xyz.wagyourtail.jvmdowngrader")

    // 1.16.5が既定で解決するLWJGL 3.3.2はmacOS（特にApple Silicon）で
    // `GLFW error 65548: Cocoa: Regular windows do not have icons on macOS`を投げて
    // Minecraft.<init>が止まる（既知の問題、LWJGL/lwjgl3#695）。新しい版へ強制する。
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.lwjgl") {
                useVersion("3.3.3")
                because("1.16.5既定のLWJGLはmacOSでウィンドウアイコン設定が例外になる")
            }
        }
    }
}

// fabric.mod.jsonの"java"依存へ渡す実行時要件。
// 1.16.5試作ノードはJava 21でコンパイルした後にJava 8へ変換する予定。
val javaVersion = javaVersionFor(minecraftVersion)
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

repositories {
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

loom {
    accessWidenerPath = rootProject.file("src/main/resources/xaeronav.accesswidener")

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

val xaeroModules = xaeroModuleCoordinates(
    "fabric", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
// このMODはXaero未導入でもワールド内描画だけで動く設計なので、その前提を実際に確かめる手段を残す。
val withXaero = withXaeroProperty()

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く。
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

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
    // 1.16.5用ModMenu 1.16.23は自身の依存にfabric-loaderを直接持つ古い形式で、
    // Loomのremapが本来のfabric-loader(0.19.5)とは別物として扱い、runClientが
    // 「duplicate fabric loader classes」で落ちる。ModMenu自身はloaderをMOD経由で
    // 読み込まないので除外して問題ない。
    modCompileOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }
    modLocalRuntime("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }

    // Xaeroはfabric.mod.json上optionalな連携先。コンパイルにだけ必要。
    // compileOnly（modの付かない方）だとMinecraftの型が中間マッピングのままで解決できない。
    xaeroModules.forEach { modCompileOnly(it) }
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

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める。
// Fabricで配るのは中間マッピングへ戻したremapJarの方で、素のjarではない
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named(if (minecraftVersion.startsWith("1.16.")) "java8Jar" else "remapJar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "fabric_loader_range" to dep("fabric_loader_range"),
        // fabric-apiは"*"のままだと古いAPIでもloaderが起動を許してしまう。開発・CIで実際に
        // ビルド・テストしている版（deps.fabric_api）を下限として宣言する——それより下は
        // 検証していないので「動く保証がある最も低い版」とは言えない
        "fabric_api_range" to dep("fabric_api"),
        "fabric_api_mod_id" to fabricApiModIdFor(minecraftVersion),
        "java_version" to javaVersion.toString()
    )

    inputs.properties(replaceProperties)

    // NeoForge/Forge側のMOD定義・AT定義はFabricのjarには要らない
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/mods.toml")
    exclude("META-INF/accesstransformer.cfg")

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

if (minecraftVersion.startsWith("1.16.")) {
    // 配布するのはJava 8へ変換した方（java8Jar）。変換前のjarは名前をずらして残す
    tasks.named<AbstractArchiveTask>("remapJar") { archiveClassifier.set("java21") }
    val downgraded = tasks.register<DowngradeJar>("downgradeRemapJar") {
        inputFile.set(tasks.named<AbstractArchiveTask>("remapJar").flatMap { it.archiveFile })
        archiveClassifier.set("java8-unshaded")
    }
    val shaded = tasks.register<ShadeJar>("shadeJava8Jar") {
        inputFile.set(downgraded.flatMap { it.archiveFile })
        archiveClassifier.set("java8-shaded")
    }
    val java8Jar = registerJava8Jar(shaded.flatMap { it.archiveFile })
    tasks.named("assemble") { dependsOn(java8Jar) }
}
