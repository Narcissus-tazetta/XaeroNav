import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("xaeronav.common")
    id("dev.architectury.loom") version "1.17.493"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
    id("com.gradleup.shadow") version "9.6.1"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")
val minecraftVersion = dep("minecraft")
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)
val xaeroModules = xaeroModuleCoordinates(
    "forge", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

loom {
    silentMojangMappingsLicense()
    // 本番のForge 1.16.5はSRG名で動く。mixinの注入先の記述（MatrixStack等の型）をSRGへ引くrefmapを作る
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("${modProperty("mod_id")}.refmap.json")
    }
    forge {
        mixinConfig("${modProperty("mod_id")}-xaero.mixins.json")
    }
}

// Forge 1.16.5がバンドルするASM/LWJGLはJava 21で動かない。JDK 21で実際に動く版へ強制する
// （https://github.com/architectury/architectury-loom/issues/320のコメント参照）。
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion("9.6")
            because("Java 21で動くASMへ強制する")
        }
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("Java 21で動くLWJGLへ強制する")
        }
    }
}

val mixinExtrasPlugin = "net.prason.xaeronav.mixin.MixinExtrasBootstrapPlugin"
val shadedMixinExtras: Configuration by configurations.creating { isTransitive = false }

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = withXaeroProperty()

val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    forge("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}")
    // Xaero's Minimap / World MapのPOMはXaeroLibの`dev`分類子（MCP名でビルドされた開発用jar）に依存している。
    // 同じモジュールを分類子あり・なしで両方引くと、Loomが変換した分類子なしのjarが空（22バイト）になる。
    // XaeroLibは分類子なしの配布jarを自分で足すので、推移的な依存はコンパイル時も実行時も切る
    xaeroModules.forEach { modCompileOnly(it) { isTransitive = false } }
    // stageRuntimeTestModsには配布時と同じ未変換jarを渡す
    xaeroModules.forEach { xaeroRuntimeMods(it) }
    if (withXaero) {
        // 公開jarはSRG名なので、run/modsへ生のまま置くと開発環境（Mojang名）のクラスが見えない。
        // Loomのmod remapを通して開発環境の名前へ変換したものを載せる（coremodの中身はfixXaeroCoremodsが直す）
        xaeroModules.forEach { modLocalRuntime(it) { isTransitive = false } }
    }
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    // @WrapOperation・@ModifyReturnValueはMixin本体のAPが知らない注入なので、これが無いとrefmapへ載らない
    annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    shadedMixinExtras("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    // 開発実行では移し替える前のMixinExtrasをそのまま使う（MixinExtrasBootstrapPluginが起動する）
    "localRuntime"("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
}

// Forge 1.16.5はMixinExtrasを同梱せず、jar-in-jarも無い。他のMODが別の版を同梱していてもぶつからないよう、
// 自分のパッケージへ移して配布jarへ入れる（起動はMixinExtrasBootstrapPlugin）。
val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations.set(listOf(shadedMixinExtras))
    relocate("com.llamalad7.mixinextras", "net.prason.xaeronav.shadow.mixinextras")
    mergeServiceFiles()
    // MixinExtras自身の注釈処理器の登録。配布jarに入れるとこのjarをクラスパスに置いたビルドで勝手に動く
    exclude("META-INF/services/javax.annotation.processing.Processor")
    archiveClassifier.set("dev-shadow")
}
// 配布するのはJava 8へ変換した方（java8Jar）。変換前のjarは名前をずらして残す
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(shadowJar.flatMap { it.archiveFile })
    archiveClassifier.set("java21")
}
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

val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(java8Jar)
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

// 専用サーバーのproduction smoke testにはXaeroを入れず、利用者へ配るjarだけを渡す。
tasks.register<Sync>("stageServerTestMod") {
    from(java8Jar)
    into(rootProject.layout.buildDirectory.dir("server-test/${stonecutter.current.project}/mods"))
}

// Loomのremapはクラスの参照しか変換しないので、Xaeroが文字列で持つSRG名（coremodのJavaScript・リフレクションの
// Class.forName）が残り、開発実行が落ちる。変換済みのjarの中身を起動前に直す（ForgeCoremodNames.kt）
val fixXaeroCoremods = tasks.register("fixXaeroCoremods") {
    val runtimeJars = configurations.named("runtimeClasspath").map { classpath ->
        classpath.files.filter { it.name.startsWith("xaero") }
    }
    doLast {
        val tiny = net.fabricmc.loom.LoomGradleExtension.get(project).mappingConfiguration.tinyMappingsWithSrg
        runtimeJars.get().forEach { jar ->
            val count = rewriteForgeCoremodNames(jar.toPath(), tiny)
            if (count > 0) {
                logger.lifecycle("${jar.name}: ${count}ファイルのSRG名を開発環境の名前へ書き換えた")
            }
        }
    }
}
tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(fixXaeroCoremods)
}

// これが無いと配布jarの META-INF/mods.toml・xaeronav-xaero.mixins.json が
// `${'$'}{mod_id}` 等の未展開プレースホルダーのまま入り、Forgeがmod定義を読めず起動しない
// （compileJava/assembleは通るのでビルドだけでは気付けない。runClientで発覚）。
tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
    )

    inputs.properties(replaceProperties)
    inputs.property("mixin_plugin", mixinExtrasPlugin)

    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")

    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
        filter { line ->
            line.replace("\"package\":", "\"plugin\": \"$mixinExtrasPlugin\",\n  \"package\":")
        }
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}
