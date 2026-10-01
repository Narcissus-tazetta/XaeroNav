import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("xaeronav.common")
    id("net.minecraftforge.gradle") version "7.0.40"
    id("net.minecraftforge.renamer") version "1.1.5"
    // FG7ではjar-in-jarが別プラグインに分離された（FG6までは組み込み）
    id("net.minecraftforge.jarjar") version "0.2.3"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")
val needsRuntimeReobfuscation = stonecutter.eval(minecraftVersion, "<1.20.5")

// xaeronav.common.gradle.ktsのtoolchain分岐と同じ境界線（このノードは今のところ常に1.21.1系なのでJAVA_21固定）
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

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

// annotation processorはmixinアノテーションの検証に使う。1.20.5以降は公式マッピングランタイムなので
// refmapは生成されない（NeoForgeと同じ理由）が、コンパイル時チェックそのものは要る。
// 1.20.4以前はSRGランタイムなので、下のenableMixinRefmapsが渡すマッピングでrefmapも作る
dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
}

// クライアント専用MOD（@Mod単体、dist引数はForgeには無い）。専用サーバーの実行設定は用意しない
minecraft {
    // NeoForge本体はRenderStateShardの定数群をワイルドカードATで開放しているが、Forge本体は
    // インナークラスしか開放していない（NavRenderTypes.javaのコメント参照）。同じ開放を足す
    // 26.1以降はATが開放していたRenderStateShard・RenderTypeの構造がもう無く（NavRenderTypes）、
    // ATの処理（Java 8で動くツール）が26.xのクライアントjarで落ちるので、開放を要るノードにだけ当てる
    if (!stonecutter.eval(minecraftVersion, ">=26.1")) {
        accessTransformer = rootProject.files("src/main/resources/META-INF/accesstransformer.cfg")
    }

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
            // `-Pxaeronav.quickPlay=<ワールド名>`でタイトル画面を飛ばして既存のワールドへ入る（手元の確認用）
            providers.gradleProperty("xaeronav.quickPlay").orNull?.let { args("--quickPlaySingleplayer", it) }
            // NeoForgeノードと同じ口（CIのruntime hook probeを手元で走らせるときなど）
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { jvmArgs(it) }
            if (!needsRuntimeReobfuscation) {
                // 開発実行はMODをクラスディレクトリから読むのでMANIFESTのMixinConfigsが存在しない
                // （Renamerを使うノードは、下のenableMixinRefmapsが同じ引数を足す）
                args("--mixin.config", "${modProperty("mod_id")}-xaero.mixins.json")
            }
        }
    }
}

// 本番がSRG名の版は、注入先の文字列をSRGへ引くrefmapが要る。開発環境でもXaero同梱refmapのSRG名を
// namedへ読み替える必要がある。RenamerのMixin連携はrunClientへrefMapRemappingFileを設定し、
// XaeroNav自身のrefmap生成・開発実行へのconfig登録も担う。
if (needsRuntimeReobfuscation) {
    renamer.enableMixinRefmaps {
        config("${modProperty("mod_id")}-xaero.mixins.json")
        // mixins.jsonの"refmap"が指す名前に揃える。既定の`main.refmap.json`だと配布jarでrefmapが見つからず、
        // SRGへ引く注入先が1本も当たらない
        refMap.set("${modProperty("mod_id")}.refmap.json")
        // dependency変換はRenamer側がreverse=trueにする一方、Mixin実行時に必要なのは
        // SRG（Xaeroのrefmap）→named（開発Minecraft）なので、出力mappingも反転する。
        generatedMappings {
            reverse.set(true)
        }
    }

    // Mixin 0.8.5のrefMapRemappingFileはSRG形式（MD:/FD:行）しか読めず、Renamerが渡すtsrgは行ごと
    // 黙って無視される。すると開発実行でXaero自身のmixinが落ちる。同じ内容をSRG形式へ変換し、
    // Renamerの設定が済んだ後にsystem propertyを差し替える。
    val mixinRefmapRemapSrg = renamer.convert("mixinRefmapRemapSrg", renamer.mixin.generatedMappings, "srg")
    tasks.matching { it.name == "runClient" }.configureEach { dependsOn(mixinRefmapRemapSrg) }
    afterEvaluate {
        minecraft.runs.named("client") {
            systemProperty("mixin.env.refMapRemappingFile", mixinRefmapRemapSrg.get().output.get().asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}"))
}

// jarJarタスクの出力（classifier無し）をそのまま配布物にする。元のjarタスクは"slim"（mixinextrasを
// 含まない）へ回し、collectJars（ルートのbuildAll成果物集約）が誤って拾わないようにする
jarJar.register {
    // 1.20.4以前は本番がSRG名なので、統合後のjarをさらにrenameしてから配布する。
    archiveClassifier = if (needsRuntimeReobfuscation) "mapped" else null
}

val distributionJar = if (needsRuntimeReobfuscation) {
    renamer.classes("renameJarJar", tasks.named<Jar>("jarJar")) {
        // mapは下のrenamer.mappings(...)が既定値として入る。ここで足すと2ファイルになりRenamerが拒否する
        archiveClassifier = null
    }
} else {
    tasks.named<AbstractArchiveTask>("jarJar")
}

if (needsRuntimeReobfuscation) {
    tasks.named("assemble") {
        dependsOn(distributionJar)
    }
}

// ForgeのFMLはmods.tomlの[[mixins]]を読まない（NeoForgeとの違い）。configを拾うのはMixin本体で、
// 見るのはMANIFESTのMixinConfigsだけ。無いとXaero連携のmixinが本番で1本も当たらない
tasks.named<Jar>("jar") {
    archiveClassifier = "slim"
    manifest.attributes("MixinConfigs" to "${modProperty("mod_id")}-xaero.mixins.json")
}

val xaeroModules = xaeroModuleCoordinates(
    "forge", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Xaeroを開発実行（runClient）へ載せるか。`./gradlew runClient -Pwith_xaero=false` で外せる。
val withXaero = withXaeroProperty()

// XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く
// （他の2ノードと同じ理由。NeoForgeEntry.javaのコメント参照）。
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

// Forge 1.20.4以前の公開Xaero jarはMinecraft参照がSRG名（f_... / m_...）のままなので、
// named開発環境へそのまま載せるとXaero自身のMixin @Shadowが解決できない。
// Renamerのdependency経路はFGのmappingを逆向きに適用し、開発実行・コンパイル用だけnamedへ直す。
// 配布時のruntime smoke testには下のxaeroRuntimeModsから未変換jarを渡す。
val xaeroDevelopmentModules = if (needsRuntimeReobfuscation) {
    renamer.mappings(minecraft.dependency.toSrg)
    xaeroModules.map { renamer.dependency(it) }
} else {
    // FG7はMinecraft依存と同じ解決構成上の外部modをmavenizerでnamedへ変換する。
    // 公開jarをrun/modsへ直接コピーすると変換を迂回し、Xaero自身の@Shadow f_... が落ちる。
    xaeroModules
}

// 26.1以降のXaero（Forge）のMaven上のjarは、META-INF/jarjar/metadata.jsonだけが残って入れ子のxaerolib本体が無い
// （配布jarには入っている）。FMLのjar-in-jar解決が「入れ子のjarが見つからない」で起動前に落ちるので、
// 開発実行へ載せるものだけmetadataを外す。xaerolibは別のjarとして同じく載せているので解決は足りる
val stripsXaeroJarJar = stonecutter.eval(minecraftVersion, ">=26.1")
val xaeroStrippedDir = layout.buildDirectory.dir("xaero-without-jarjar")
val stripXaeroJarJar = tasks.register("stripXaeroJarJar") {
    val source = configurations.detachedConfiguration(*xaeroModules.map { dependencies.create(it) }.toTypedArray())
        .apply { isTransitive = false }
    inputs.files(source)
    outputs.dir(xaeroStrippedDir)
    doLast {
        val outDir = xaeroStrippedDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        source.files.forEach { jar ->
            ZipFile(jar).use { zip ->
                ZipOutputStream(File(outDir, jar.name).outputStream()).use { out ->
                    zip.entries().asSequence().filterNot { it.name.startsWith("META-INF/jarjar/") }.forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

dependencies {
    xaeroDevelopmentModules.forEach { compileOnly(it) }
    if (withXaero && stripsXaeroJarJar) {
        runtimeOnly(fileTree(xaeroStrippedDir) { builtBy(stripXaeroJarJar) })
    } else if (withXaero) {
        xaeroDevelopmentModules.forEach { runtimeOnly(it) }
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
    if (needsRuntimeReobfuscation) {
        // @WrapOperation・@ModifyReturnValueはMixin本体のAPが知らない注入なので、SRGのrefmapを作る
        // 1.20.4以前ではAPにも載せる。無いとrefmapが空になる（forge-legacyと同じ）
        annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    }
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

// CIの起動スモークテスト（mc-runtime-test）へ渡す一式。配布jarとXaeroを1箇所へ集める。
// mixinextrasを同梱した統合jar（jarJarタスクの出力）を使う——素のjarタスクは"slim"で
// mixinextrasを含まないため、それだけを配布・実行すると起動時にMixinExtrasが見つからず落ちる
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    if (stripsXaeroJarJar) {
        // 26.1以降はMaven版をそのまま置くとjar-in-jar解決で落ちるので、開発実行と同じmetadataを外したjarを置く
        from(stripXaeroJarJar)
    } else {
        from(xaeroRuntimeMods)
    }
    from(distributionJar)
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

// 専用サーバーのproduction smoke testにはXaeroを入れず、利用者へ配る統合jarだけを渡す。
// client runtimeと同じstage先を共有すると、任意依存のXaeroがサーバーへ混ざって検査にならない。
tasks.register<Sync>("stageServerTestMod") {
    from(distributionJar)
    into(rootProject.layout.buildDirectory.dir("server-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
    ) + if (packFormat >= 65) {
        mapOf("pack_format_fields" to packFormatFields(packFormat, dataPackFormatFor(minecraftVersion)))
    } else {
        emptyMap()
    }

    inputs.properties(replaceProperties)

    if (stonecutter.eval(minecraftVersion, ">=26.1")) {
        exclude("META-INF/accesstransformer.cfg")
    }
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
