import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Forge 1.16.5向けのMODが持つcoremod（JavaScript）の中のSRG名を、開発環境の名前（Mojang名）へ書き換える。
 *
 * <p>Xaeroのcoremodは注入先のクラス・メソッドをSRG名の文字列で持っている（例: `ToggleableKeyBinding`・`func_151470_d`）。
 * 本番のForge 1.16.5はSRG名で動くので問題ないが、Loomの開発環境はMojang名で動き、Loomのremapはクラスファイルしか
 * 書き換えない。そのままだと開発実行（runClient）が`NoClassDefFoundError: ToggleableKeyBinding`で起動しない。
 *
 * <p>jarをその場で書き換える。書き換え済みのjarにはSRG名が残っていないので、何度呼んでも結果は変わらない。
 *
 * @param tinyWithSrg Loomの`mappings-srg.tiny`（名前空間に`srg`と`named`を持つ）
 * @return 書き換えたJavaScriptの数
 */
fun rewriteForgeCoremodNames(jar: Path, tinyWithSrg: Path): Int {
    val names = SrgToNamed.load(tinyWithSrg)
    var rewritten = 0
    FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), emptyMap<String, Any>()).use { zip ->
        val scripts = Files.walk(zip.getPath("/")).use { paths ->
            paths.filter { it.toString().endsWith(".js") }.toList()
        }
        for (script in scripts) {
            val before = Files.readString(script)
            val after = names.rewrite(before)
            if (after != before) {
                Files.writeString(script, after)
                rewritten++
            }
        }
    }
    return rewritten
}

private class SrgToNamed(private val classes: Map<String, String>, private val members: Map<String, String>) {

    fun rewrite(script: String): String {
        val withClasses = CLASS_NAME.replace(script) { match -> mapClass(match.value) ?: match.value }
        return MEMBER_NAME.replace(withClasses) { match -> members[match.value] ?: match.value }
    }

    /** `a.b.C.method`のように後ろへ続く場合があるので、対応表に当たるまで末尾を削って探す。 */
    private fun mapClass(name: String): String? {
        val dotted = name.contains('.')
        var candidate = name.replace('.', '/')
        while (true) {
            classes[candidate]?.let { named ->
                val rest = name.substring(candidate.length)
                return (if (dotted) named.replace('/', '.') else named) + rest
            }
            val cut = candidate.lastIndexOf('/')
            if (cut <= "net/minecraft".length) {
                return null
            }
            candidate = candidate.substring(0, cut)
        }
    }

    companion object {
        private val CLASS_NAME = Regex("""net[./]minecraft[./][\w$./]*[\w$]""")
        private val MEMBER_NAME = Regex("""\b(?:func|field)_\d+_[A-Za-z]+_?\b""")

        fun load(tiny: Path): SrgToNamed {
            val lines = Files.readAllLines(tiny)
            val header = lines.first().split('\t')
            check(header[0] == "tiny" && header[1] == "2") { "tiny v2ではない: $tiny" }
            val namespaces = header.drop(3)
            val srg = namespaces.indexOf("srg")
            val named = namespaces.indexOf("named")
            check(srg >= 0 && named >= 0) { "srgとnamedの名前空間が無い: $tiny ($namespaces)" }
            val classes = HashMap<String, String>()
            val members = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val columns = line.split('\t')
                when {
                    columns[0] == "c" -> classes[columns[1 + srg]] = columns[1 + named]
                    // メソッド・フィールドの行はクラスの下に1段下げて`m`/`f`、記述子、名前空間ごとの名前の順
                    columns.size > 3 && columns[0].isEmpty() && (columns[1] == "m" || columns[1] == "f") ->
                        members[columns[3 + srg]] = columns[3 + named]
                }
            }
            return SrgToNamed(classes, members)
        }
    }
}
