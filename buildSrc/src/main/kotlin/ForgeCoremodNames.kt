import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Forge 1.16.5向けのMODが文字列で持っているSRG名（1.16ではMCP名と同じクラス名も含む）を、開発環境の名前（Mojang名）へ書き換える。
 *
 * <p>本番のForge 1.16.5はSRG名で動くので問題ないが、Loomの開発環境はMojang名で動き、Loomのremapはクラスファイルの
 * 型・メンバーの参照しか書き換えない。Xaeroは2か所で文字列を使っていて、そのままだと開発実行（runClient）が落ちる。
 * <ul>
 *   <li>coremod（JavaScript）の注入先: `ToggleableKeyBinding`・`func_151470_d` → 起動時に`NoClassDefFoundError`</li>
 *   <li>リフレクションの`Class.forName("net.minecraft.client.renderer.RenderType$Type")` → ワールドに入ると`ClassNotFoundException`</li>
 * </ul>
 * クラスファイルの中は、文字列全体がクラス名であるものだけを書き換える（メンバー名はForgeの`ObfuscationReflectionHelper`が引く）。
 *
 * <p>jarをその場で書き換える。書き換え済みのjarにはSRG名が残っていないので、何度呼んでも結果は変わらない。
 *
 * @param tinyWithSrg Loomの`mappings-srg.tiny`（名前空間に`srg`と`named`を持つ）
 * @return 書き換えたファイルの数
 */
fun rewriteForgeCoremodNames(jar: Path, tinyWithSrg: Path): Int {
    val names = SrgToNamed.load(tinyWithSrg)
    var rewritten = 0
    FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), emptyMap<String, Any>()).use { zip ->
        val files = Files.walk(zip.getPath("/")).use { paths ->
            paths.filter { it.toString().endsWith(".js") || it.toString().endsWith(".class") }.toList()
        }
        for (file in files) {
            if (file.toString().endsWith(".js")) {
                val before = Files.readString(file)
                val after = names.rewrite(before)
                if (after != before) {
                    Files.writeString(file, after)
                    rewritten++
                }
            } else {
                val after = names.rewriteClassNameStrings(Files.readAllBytes(file))
                if (after != null) {
                    Files.write(file, after)
                    rewritten++
                }
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

    /** 文字列定数のうち、全体がMinecraftのクラス名であるものを書き換える。何も変えなければ`null`。 */
    fun rewriteClassNameStrings(bytes: ByteArray): ByteArray? {
        val reader = ClassReader(bytes)
        var changed = false
        // フレームや最大スタックは文字列定数を差し替えても変わらないので、計算し直さない（COMPUTE_*を付けない）
        val writer = ClassWriter(reader, 0)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                override fun visitLdcInsn(value: Any?) {
                    val mapped = (value as? String)?.let { exactClass(it) }
                    if (mapped != null) {
                        changed = true
                        super.visitLdcInsn(mapped)
                    } else {
                        super.visitLdcInsn(value)
                    }
                }
            }
        }, 0)
        return if (changed) writer.toByteArray() else null
    }

    private fun exactClass(name: String): String? {
        if (!name.startsWith("net.minecraft.") && !name.startsWith("net/minecraft/")) {
            return null
        }
        val named = classes[name.replace('.', '/')] ?: return null
        return if (name.contains('.')) named.replace('/', '.') else named
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
