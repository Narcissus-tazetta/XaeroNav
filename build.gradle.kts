plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.140"
    id("com.diffplug.spotless") version "8.9.0"
}

fun propOrNull(key: String) = project.findProperty(key) as String?
fun prop(key: String) = propOrNull(key) ?: error("Property `$key` not set.")

version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = prop("mod_id")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://chocolateminecraft.com/maven") { name = "Xaero's Maven" }
}

neoForge {
    version = prop("neoforge_version")

    mods {
        create(prop("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    // 単体テストからMinecraftの素の値型（BlockPos・Vec3・Mth）を使えるようにする。
    // これが無いとtestCompileClasspathにMinecraftが載らず、経路探索コアのテストは
    // 1行も書けない（既存テストがHeuristic等のMinecraft非依存クラスに限られていたのはこのため）。
    // なお、ここで載るのはクラスパスだけで、Blocks/BuiltInRegistriesに触るにはBootstrapが要る。
    // テストはレジストリを起動しなくても動く範囲に留めること。
    addModdingDependenciesTo(sourceSets["test"])

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", prop("mod_id"))
        }
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }
}

// artifactIdは "-forge-" ではなく "-neoforge-"。chocolateminecraft.comのmavenには両方存在し、
// "-forge-"版はNeoForge実行時に「Forge用/古いNeoForge用のため読み込めません」で無視される。
val xaeroModules = listOf(
    "xaero.lib:xaerolib-neoforge-${prop("minecraft_version")}:${prop("xaerolib_version")}",
    "xaero.map:xaeroworldmap-neoforge-${prop("minecraft_version")}:${prop("xaero_worldmap_version")}",
    "xaero.minimap:xaerominimap-neoforge-${prop("minecraft_version")}:${prop("xaero_minimap_version")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
// このMODはXaero未導入でもワールド内描画だけで動く設計なので、その前提を実際に確かめる手段を残す
// （xaeronav-xaero.mixins.jsonはrequired=falseなので、Xaeroが無ければ地図連携だけが黙って無効になる）。
val withXaero = propOrNull("with_xaero")?.toBoolean() ?: true

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
    into(layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = mapOf(
        "minecraft_version" to prop("minecraft_version"),
        "neoforge_loader_version_range" to prop("neoforge_loader_version_range"),
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_version" to prop("mod_version"),
        "mod_authors" to prop("mod_authors"),
        "mod_description" to prop("mod_description"),
        "mod_license" to prop("mod_license"),
        "mod_display_url" to prop("mod_display_url"),
        "mod_issue_tracker_url" to prop("mod_issue_tracker_url"),
        "xaero_worldmap_version" to prop("xaero_worldmap_version"),
        "xaero_minimap_version" to prop("xaero_minimap_version")
    )

    inputs.properties(replaceProperties)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn("prepareClientRun")
}

// フォーマットの取り締まりはここまで: 既存の書式（4スペース）を丸ごと書き換える整形器は
// 導入しない。差分が全ファイルに及んで意味のあるレビューができなくなる方が、崩れた書式が
// たまに残るより害が大きい。ここで見るのは「直しても議論の余地がない」項目だけ
// （未使用importの残骸、行末の余分な空白、ファイル末尾の改行漏れ）。
spotless {
    java {
        target("src/*/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("6.1.3")
        }
    }
}
