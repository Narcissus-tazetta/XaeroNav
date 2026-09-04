import org.gradle.api.Project

/** `gradle.properties` に置いたMOD自身のメタデータ。ノードによらず同じ値。 */
fun Project.modProperty(key: String): String =
    findProperty(key) as String? ?: error("Property `$key` not set.")

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
