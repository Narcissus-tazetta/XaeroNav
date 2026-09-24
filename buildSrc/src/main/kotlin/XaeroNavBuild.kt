import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** `gradle.properties` に置いたMOD自身のメタデータ。ノードによらず同じ値。 */
fun Project.modProperty(key: String): String =
    findProperty(key) as String? ?: error("Property `$key` not set.")

/** 各Minecraft版で利用者に必要となるJavaの最低バージョン。 */
fun javaVersionFor(minecraftVersion: String): Int = when {
    minecraftVersion.startsWith("1.16.") -> 8
    minecraftVersion.startsWith("1.20.") -> 17
    else -> 21
}

// 1.16.5 のソースは現行の record 等を使うため、Java 21 でコンパイルしてから
// 配布 jar を Java 8 向けへ変換する。実行時の要件は javaVersionFor が表す。
fun compileJavaVersionFor(minecraftVersion: String): Int =
    if (minecraftVersion.startsWith("1.16.")) 21 else javaVersionFor(minecraftVersion)

// MixinはCompatibilityLevelを実行時要件（javaVersionFor）ではなく、mixinクラス自身の
// バイトコードが要求する言語機能で判定する。1.16.5はJava 21でコンパイルしてから配布時に
// Java 8へ変換するため、コンパイル直後（=runClientが使う開発ビルド）のmixinクラスは
// NESTING等のJava 11以降の機能を含む。javaVersionForの8をそのまま渡すとMixinが
// 「JAVA_8ではNESTINGを扱えない」として拒否し起動しない。
//
// 1.16.5だけは21ではなく18に留める。Forge 1.16.5がバンドルするMixinフォーク
// （architectury mixin-patched 0.8.4.12）は`CompatibilityLevel`列举がJAVA_18までしか無く、
// JAVA_21を渡すとMixin初期化そのものが起動前に例外で落ちる（enumに存在しない値）。
// 1.16.5のmixinクラスが実際に要る機能はNESTING（Java 11以降）だけなので18で十分。
// 他バージョンは元々の値のままで、ここを変えると（Fabricの新しいMixinでは21が通っている）
// 意図せず動作を変えてしまう。
fun mixinCompatibilityLevelFor(minecraftVersion: String): String {
    val compileVersion = compileJavaVersionFor(minecraftVersion)
    val level = if (minecraftVersion.startsWith("1.16.")) minOf(compileVersion, 18) else compileVersion
    return "JAVA_$level"
}

/**
 * Fabric APIの本体モジュールが名乗るmod id。1.16.5時代の0.42.0系は"fabric"のまま
 * （"fabric-api"への改名は後続バージョンから）で、依存宣言のキーを間違えると
 * 実際には入っているのに「fabric-apiが無い」と判定されてmod解決が落ちる。
 */
fun fabricApiModIdFor(minecraftVersion: String): String =
    if (minecraftVersion.startsWith("1.16.")) "fabric" else "fabric-api"

/** リソースパックのpack_format。クライアントjarのversion.jsonの`pack_version.resource_major`。 */
fun packFormatFor(minecraftVersion: String): Int = when (minecraftVersion) {
    "1.16.5" -> 6
    "1.20.1" -> 15
    "1.21.1" -> 34
    "1.21.11" -> 75
    else -> error("pack_formatが未登録のMinecraft $minecraftVersion。クライアントjarのversion.jsonから足すこと")
}

/**
 * Xaeroの3モジュール（lib/worldmap/minimap）の依存座標。artifactId中のloader名部分
 * （fabric/forge/neoforge）だけが4ノードで違う。
 */
fun xaeroModuleCoordinates(
    loader: String,
    minecraftVersion: String,
    xaerolibVersion: String,
    worldmapVersion: String,
    minimapVersion: String,
): List<String> = listOf(
    "xaero.lib:xaerolib-$loader-$minecraftVersion:$xaerolibVersion",
    "xaero.map:xaeroworldmap-$loader-$minecraftVersion:$worldmapVersion",
    "xaero.minimap:xaerominimap-$loader-$minecraftVersion:$minimapVersion",
)

/** `./gradlew runClient -Pwith_xaero=false` でXaeroを外せるようにする開発実行の共通判定。 */
fun Project.withXaeroProperty(): Boolean = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

/**
 * XaeroはMODとして読み込ませる必要があるので、実行時クラスパスではなくrun/modsへ置く
 * （4ノード共通の理由。各ノードのbuild.*.gradle.ktsコメント参照）。
 */
fun Project.createXaeroRuntimeModsConfiguration(): Configuration =
    configurations.create("xaeroRuntimeMods") { isTransitive = false }

/**
 * 実機デバッグ用に、バージョンへgitの短縮ハッシュを付ける（例: "0.1.2+f118060"）。
 * 「治ってない」報告が再ビルド未反映によるものかを`/xaeronav version`で見分けられるようにするため。
 *
 * <p>リリースビルド（`-Prelease`）では付けない。配布物のバージョンはタグ名と一致させたい。
 * gitが無い・リポジトリ外（GitHubのソースzipを展開しただけ等）ならハッシュ無しに落とす
 * ——ここで失敗させると、リリースjarをソースから組み直したい人がビルドできない。
 */
fun Project.stampedModVersion(): String {
    val base = modProperty("mod_version")
    if (hasProperty("release")) {
        return base
    }
    return gitCommitHash()?.let { "$base+$it" } ?: base
}

/**
 * ファイル名・成果物名に使うバージョン。semverのbuild-metadataの区切り `+` はファイル名に
 * 向かない（URLエンコードされる・ツールによっては扱いが割れる）ので `-` にする。
 *
 * <p><b>MODのメタデータ側は{@code stampedModVersion}のまま `+` を使う</b>——あちらはsemverとして
 * 解釈されるので、build-metadataの区切りを変えると別のバージョンになってしまう。
 *
 * <p>成果物名を組む所とそれを拾う所（{@code collectJars}）で別々に書くと、片方だけ変えたときに
 * <b>1つも拾えないまま緑になる</b>。実際そうなっていた——jarは `-` で作られ、拾う側は `+` で
 * 探していたので{@code build/libs}が空になり、CIのjarアップロード（{@code if-no-files-found: error}）と
 * リリースが落ちる状態だった。1か所に寄せて二度と割れないようにする。
 */
fun Project.archiveModVersion(): String = stampedModVersion().replace('+', '-')

/**
 * 配布jarのファイル名 `<mod_id>-<この値>.jar` の後半部分。
 * `<mod_version>-<ローダー>-<MCバージョン>[-<gitハッシュ>]`。
 *
 * <p>jarタスクの{@code archiveVersion}と{@code collectJars}のincludeパターンの両方でこれを使う。
 * 片方だけ変えると{@code build/libs}が空のままCIが緑になる（{@code archiveModVersion}のコメント参照）。
 */
fun Project.archiveVersionFor(loader: String, minecraftVersion: String): String {
    val version = modProperty("mod_version")
    val hashSuffix = archiveModVersion().removePrefix(version)
    return "$version-$loader-$minecraftVersion$hashSuffix"
}

private fun Project.gitCommitHash(): String? {
    val output = runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }
    }.getOrNull() ?: return null
    val exitValue = runCatching { output.result.get().exitValue }.getOrNull()
    if (exitValue != 0) {
        return null
    }
    return output.standardOutput.asText.get().trim().ifEmpty { null }
}

/**
 * 両ローダーのMOD定義ファイル（`neoforge.mods.toml` / `fabric.mod.json`）へ差し込む共通の値。
 * ローダー固有の値（loaderのバージョン範囲など）は各ビルドスクリプトで足す。
 */
fun Project.modResourceProperties(): Map<String, String> = mapOf(
    "mod_id" to modProperty("mod_id"),
    "mod_name" to modProperty("mod_name"),
    "mod_version" to stampedModVersion(),
    "mod_authors" to modProperty("mod_authors"),
    "mod_description" to modProperty("mod_description"),
    "mod_license" to modProperty("mod_license"),
    "mod_display_url" to modProperty("mod_display_url"),
    "mod_issue_tracker_url" to modProperty("mod_issue_tracker_url")
)

/**
 * 4ノード共通のresource置換値（{@link #modResourceProperties}に加え、Xaeroの動く下限と
 * pack_format/mixin互換レベル）。loader固有のキー（loaderのバージョン範囲など）は
 * 各build.<loader>.gradle.ktsが呼び出し側で足す。
 */
fun Project.commonNodeResourceProperties(
    minecraftVersion: String,
    worldmapMinVersion: String,
    minimapMinVersion: String,
    mixinCompatibilityLevel: String,
    packFormat: Int,
): Map<String, String> = modResourceProperties() + mapOf(
    "minecraft_version" to minecraftVersion,
    "xaero_worldmap_min_version" to worldmapMinVersion,
    "xaero_minimap_min_version" to minimapMinVersion,
    "mixin_compatibility_level" to mixinCompatibilityLevel,
    "pack_format_fields" to packFormatFields(packFormat),
)

/**
 * `pack.mcmeta`の形式の宣言。1.21.9（リソース形式65）以降は`pack_format`ではなく`min_format`/`max_format`で書く。
 *
 * <p>Forge・NeoForgeは同じ`pack.mcmeta`をデータパックとしても読み、データ側（形式81以下を名乗るなら`supported_formats`が要る）と
 * リソース側（65以上を名乗るなら`supported_formats`を書いてはいけない）の両方を満たす書き方は無い。Forge自身と同じく
 * データの形式で宣言する。MODのリソースは互換の判定によらず読み込まれる。
 */
fun packFormatFields(packFormat: Int, dataPackFormat: Int? = null): String = when {
    dataPackFormat != null -> "\"min_format\": $dataPackFormat,\n        \"max_format\": $dataPackFormat,"
    packFormat >= 65 -> "\"min_format\": $packFormat,\n        \"max_format\": $packFormat,"
    else -> "\"pack_format\": $packFormat,"
}

/** データパックの形式（クライアントjarのversion.jsonの`pack_version.data_major`）。1.21.9以降のForge・NeoForgeだけが使う。 */
fun dataPackFormatFor(minecraftVersion: String): Int = when (minecraftVersion) {
    "1.21.11" -> 94
    else -> error("データパックの形式が未登録のMinecraft $minecraftVersion。クライアントjarのversion.jsonから足すこと")
}

/**
 * Java 8へ変換済みのjarを、配布できる形へ仕上げる。分類子の無い名前（他ノードの配布jarと同じ形）で出すので、
 * 変換前のremapJarには分類子を付けて名前をずらしておくこと。
 *
 * <p>mixin configの`compatibilityLevel`は開発実行（Java 21のままのクラス）に合わせてあるが、
 * Java 8のJVMでは`JAVA_8`より上をMixinが受け付けず、起動前に落ちる。クラスはすでにJava 8へ
 * 変換されているので、配布jarの中だけ`JAVA_8`へ書き換える。
 */
fun Project.registerJava8Jar(shaded: Provider<RegularFile>): TaskProvider<Zip> =
    tasks.register<Zip>("java8Jar") {
        from(zipTree(shaded)) {
            filesMatching("*.mixins.json") {
                filter { line -> line.replace(Regex("\"JAVA_\\d+\""), "\"JAVA_8\"") }
            }
        }
        archiveBaseName.set(tasks.named<Jar>("jar").flatMap { it.archiveBaseName })
        archiveVersion.set(tasks.named<Jar>("jar").flatMap { it.archiveVersion })
        archiveClassifier.set("")
        archiveExtension.set("jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
