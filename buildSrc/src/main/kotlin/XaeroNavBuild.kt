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
