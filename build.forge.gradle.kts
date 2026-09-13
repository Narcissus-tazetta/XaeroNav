plugins {
    id("xaeronav.common")
    id("net.minecraftforge.gradle") version "7.0.36"
    // FG7ではjar-in-jarが別プラグインに分離された（FG6までは組み込み）
    id("net.minecraftforge.jarjar") version "0.2.3"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

// xaeronav.common.gradle.ktsのtoolchain分岐と同じ境界線。xaeronav-xaero.mixins.jsonの
// compatibilityLevelへ渡す（このノードは今のところ常に1.21.1系なのでJAVA_21固定）
val mixinCompatibilityLevel = if (minecraftVersion.startsWith("1.20.")) "JAVA_17" else "JAVA_21"
// リソースパックのpack_format（Minecraft Wikiのpack format表どおり、1.20.1系は15・1.21.1系は34）
val packFormat = if (minecraftVersion.startsWith("1.20.")) 15 else 34

repositories {
    // Minecraft 1.21.1のmacOS用LWJGLにはMaven Centralに無い
    // `natives-macos-patch` classifierが含まれる。通常のMavenメタデータ解決でCentralへ
    // 固定されないよう、Minecraft公式ライブラリ置き場をartifact patternでも登録する。
    exclusiveContent {
        forRepository {
            ivy {
                name = "MojangLibraryArtifacts"
                url = uri("https://libraries.minecraft.net")
                patternLayout {
                    artifact("[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]")
                    setM2compatible(true)
                }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("org.lwjgl", "lwjgl-freetype") }
    }
    mavenCentral()
    maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
    maven("https://libraries.minecraft.net") { name = "Mojang" } // com.mojang:text2speech 等がここにしか無い
    minecraft.mavenizer(this) // FG7が作るローカルrepo
}

// annotation processorはmixinアノテーションの検証に使う。公式マッピングランタイムなのでrefmapは
// 生成されない（NeoForgeと同じ理由）が、コンパイル時チェックそのものは要る
dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
}

// クライアント専用MOD（@Mod単体、dist引数はForgeには無い）。専用サーバーの実行設定は用意しない
minecraft {
    // NeoForge本体はRenderStateShardの定数群をワイルドカードATで開放しているが、Forge本体は
    // インナークラスしか開放していない（NavRenderTypes.javaのコメント参照）。同じ開放を足す
    accessTransformer = rootProject.files("src/main/resources/META-INF/accesstransformer.cfg")

    runs {
        configureEach {
            // MC版・ローダーの異なるXaero jarを同じmodsへ混在させない。
            workingDir = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.project}")
        }

        // NeoForgeノード追加時に踏んだIDEモジュール束縛の不一致（xaeronav-multiloader-plan参照）と
        // 同種の問題がFG7でも起きるかは未確認。まずは既定のまま作り、実機で崩れたらdisableIdeRun相当を探す
        register("client") {
            mods {
                create(modProperty("mod_id")) {
                    sources(sourceSets["main"])
                }
            }
            // FG7のSlime LauncherはMinecraftのversion metadataにあるこのmacOS用引数を
            // runClientへ引き継がない。無いとGLFWがfirst thread検査で起動直後に停止する。
            if (System.getProperty("os.name").startsWith("Mac")) {
                jvmArgs("-XstartOnFirstThread")
            }
            // 開発実行はMODをクラスディレクトリから読むのでMANIFESTのMixinConfigsが存在しない
            args("--mixin.config", "${modProperty("mod_id")}-xaero.mixins.json")
        }
    }
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}"))
}

// jarJarタスクの出力（classifier無し）をそのまま配布物にする。元のjarタスクは"slim"（mixinextrasを
// 含まない）へ回し、collectJars（ルートのbuildAll成果物集約）が誤って拾わないようにする
jarJar.register {
    archiveClassifier = null
}

// ForgeのFMLはmods.tomlの[[mixins]]を読まない（NeoForgeとの違い）。configを拾うのはMixin本体で、
// 見るのはMANIFESTのMixinConfigsだけ。無いとXaero連携のmixinが本番で1本も当たらない
tasks.named<Jar>("jar") {
    archiveClassifier = "slim"
    manifest.attributes("MixinConfigs" to "${modProperty("mod_id")}-xaero.mixins.json")
}

val xaeroModules = listOf(
    "xaero.lib:xaerolib-forge-$minecraftVersion:${dep("xaerolib")}",
    "xaero.map:xaeroworldmap-forge-$minecraftVersion:${dep("xaero_worldmap")}",
    "xaero.minimap:xaerominimap-forge-$minecraftVersion:${dep("xaero_minimap")}"
)

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く
// （他の2ノードと同じ理由。NeoForgeEntry.javaのコメント参照）。
val xaeroRuntimeMods: Configuration = configurations.create("xaeroRuntimeMods") {
    isTransitive = false
}

dependencies {
    // FG7はMinecraft依存と同じ解決構成上の外部modをmavenizerでnamedへ変換する。
    // 公開jarをrun/modsへ直接コピーすると変換を迂回し、Xaero自身の@Shadow f_... が落ちる。
    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { runtimeOnly(it) }
        // stageRuntimeTestModsには配布時と同じ未変換jarを渡す。
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }

    // Forgeは本体にMixinExtrasを同梱していない（NeoForge/Fabricとの違い）。jar-in-jarで同梱する
    // （"jarJar"はnet.minecraftforge.jarjarプラグインが作るConfiguration名。関数ではない）。
    // compileOnlyでmixinextras-commonをコンパイル時クラスパスに足す（@Local/@WrapOperation等の
    // アノテーション自体の解決に要る）。annotationProcessorには足さない——足すと
    // mixinextras-common自身がMixin APへ独自のobfuscation解決経路を割り込ませ、公式マッピング
    // ランタイム(1.21.1)では常に「マッピング無し」を検知してビルドを止める。annotationProcessorは
    // Mixin本体（0.8.7）だけで足り、@ModifyReturnValue/@WrapOperationの展開自体はそちらで進む
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める。
// mixinextrasを同梱した統合jar（jarJarタスクの出力）を使う——素のjarタスクは"slim"で
// mixinextrasを含まないため、それだけを配布・実行すると起動時にMixinExtrasが見つからず落ちる
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named("jarJar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = modResourceProperties() + mapOf(
        "minecraft_version" to minecraftVersion,
        "forge_loader_version_range" to dep("forge_loader_range"),
        "xaero_worldmap_version" to dep("xaero_worldmap"),
        "xaero_minimap_version" to dep("xaero_minimap"),
        "mixin_compatibility_level" to mixinCompatibilityLevel,
        "pack_format" to packFormat.toString()
    )

    inputs.properties(replaceProperties)

    // 他の2ローダーのMOD定義はForgeのjarには要らない
    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")
    // accesstransformer.cfgは逆に含める（Forge本体が実行時にも同じ変換を配布先の環境へ
    // 適用するため）。除外するのはNeoForge/Fabric側（build.neoforge/fabric.gradle.kts）

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
