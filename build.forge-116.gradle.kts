import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("xaeronav.common")
    id("dev.architectury.loom") version "1.17.493"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
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

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = withXaeroProperty()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    forge("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}")
    xaeroModules.forEach { modCompileOnly(it) }
    if (withXaero) {
        // Forge本体はFabricのような「run/modsに置いた生jarをdev環境が実行時remapする」
        // 仕組みを持たない。公開されているXaeroのForge向けjarはSRG/Forge独自の命名で
        // ビルドされているため、生のままrun/modsへ置くと`NoClassDefFoundError`で落ちる
        // （`net.minecraft.util.ResourceLocation`等、Mojang公式名のクラスが見えない）。
        // `modLocalRuntime`はLoomの通常のmod remap経路を通すので、Architectury Loomの
        // 開発環境（公式Mojangマッピング）向けに変換された版がruntime classpathへ乗る。
        xaeroModules.forEach { modLocalRuntime(it) }
    }
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
}

val downgraded = tasks.register<DowngradeJar>("downgradeRemapJar") {
    inputFile.set(tasks.named<AbstractArchiveTask>("remapJar").flatMap { it.archiveFile })
    archiveClassifier.set("java8-unshaded")
}
val java8Jar = tasks.register<ShadeJar>("java8Jar") {
    inputFile.set(downgraded.flatMap { it.archiveFile })
    archiveClassifier.set("java8")
}
tasks.named("assemble") { dependsOn(java8Jar) }

// これが無いと配布jarの META-INF/mods.toml・xaeronav-xaero.mixins.json が
// `${'$'}{mod_id}` 等の未展開プレースホルダーのまま入り、Forgeがmod定義を読めず起動しない
// （compileJava/assembleは通るのでビルドだけでは気付けない。runClientで発覚）。
tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap"), dep("xaero_minimap"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
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
