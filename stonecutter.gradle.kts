plugins {
    id("dev.kikugie.stonecutter")
    id("com.diffplug.spotless") version "8.10.1"
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

// spotlessの整形器（google-java-format）を落としてくるためだけのリポジトリ。
// ノードのビルドが使う依存はそれぞれのbuild.<ローダー>.gradle.ktsが持つ。
repositories {
    mavenCentral()
}

// 有効なノードをファイルに残す。ノードを切り替えるとStonecutterはsrc/を書き換えるので、
// 「いまどのノードのソースが置かれているか」がgitの外に必要になる。
stonecutter.active(file(".sc_active_version"))

stonecutter.parameters {
    // ノード名 `1.21.1-neoforge` の末尾がそのままローダー名。これで各ソースの
    // `//? if neoforge {` / `//? if fabric {` / `//? if forge {` が切り替わる。
    constants.match(current.project.substringAfterLast('-'), "neoforge", "fabric", "forge")

    // 名前だけが変わったクラスは、使う箇所ごとに`//?`で分けず、ソース全体の置換で吸収する。
    // 置換は双方向（ノードを戻すと元の名前へ戻る）なので、置換後の名前をソースに直接書かないこと
    replacements {
        string(eval(current.version, ">=1.21.11")) {
            replace("ResourceLocation", "Identifier")
        }
        string(eval(current.version, ">=1.21.11")) {
            replace("net.minecraft.world.entity.vehicle.Boat;", "net.minecraft.world.entity.vehicle.boat.Boat;")
        }
    }
}

// 公開は1ジョブにつき1サイト・1ノード。失敗したジョブだけを再実行でき、
// 既に成功した別ノードを重複投稿しない。通常ビルドでは公開先を登録しない。
val publishTarget = providers.gradleProperty("publish_target").orNull
val publishNode = providers.gradleProperty("publish_node").orNull
if (publishTarget != null || publishNode != null) {
    check(publishTarget in setOf("modrinth", "curseforge") && publishNode != null) {
        "公開には -Ppublish_target=modrinth|curseforge と -Ppublish_node=<ノード> の両方が必要です"
    }
    val node = stonecutter.versions.singleOrNull { it.project == publishNode }
        ?: error("不明な公開ノード: $publishNode")
    val loader = node.project.substringAfterLast('-')
    val releaseVersion = modProperty("mod_version")
    val releaseFile = layout.buildDirectory.file(
        "libs/${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar")

    publishMods {
        file.set(releaseFile)
        // 同じプロジェクトに5ファイルを投稿するため、サイト上のversion番号はノードごとに一意にする。
        version.set("$releaseVersion-$loader-${node.version}")
        displayName.set("XaeroNav $releaseVersion - $loader ${node.version}")
        changelog.set(providers.fileContents(
            layout.projectDirectory.file("changelogs/$releaseVersion.md")).asText)
        type.set(STABLE)
        dryRun.set(providers.gradleProperty("publish_dry_run").map(String::toBoolean).orElse(false))
        modLoaders.add(loader)

        when (publishTarget) {
            "modrinth" -> modrinth {
                projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
                accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
                minecraftVersions.add(node.version)
                environment.set(CLIENT_ONLY)
                if (loader == "fabric") requires("fabric-api")
            }
            "curseforge" -> curseforge {
                projectId.set(providers.environmentVariable("CURSEFORGE_PROJECT_ID"))
                accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
                minecraftVersions.add(node.version)
                client.set(true)
                server.set(false)
                if (loader == "fabric") requires("fabric-api")
            }
        }
    }
}

// 全ノードをまとめて回すための入口。ノードを増やしてもCIの記述は変わらない。
tasks.register("buildAll") {
    group = "build"
    description = "すべてのノード（MCバージョン×ローダー）をビルドする"
    dependsOn(stonecutter.tasks.named("build"))
}

// Copyではなくsync。Copyだと前のビルドのjarが残り、バージョンやコミットハッシュの違う
// 古い成果物がそのままリリースに添付されうる（release.ymlはbuild/libs/*.jarを丸ごと拾う）
tasks.register<Sync>("collectJars") {
    group = "build"
    description = "全ノードの配布jarをルートのbuild/libsへ集める"
    dependsOn(tasks.named("buildAll"))
    // ノード側のbuild/libsにも過去のビルドのjarが残る（jarタスクは古い出力を消さない）。
    // 今回のバージョンのものだけを拾う——バージョンにはgitの短縮ハッシュが付くので、
    // これで「このビルドが作ったjar」だけに絞れる
    stonecutter.versions.forEach { node ->
        val loader = node.project.substringAfterLast('-')
        from(layout.projectDirectory.dir("versions/${node.project}/build/libs")) {
            include("${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar")
        }
    }
    into(layout.buildDirectory.dir("libs"))
}

// Release前に集約した成果物の契約を見る。ファイルが5個あるだけでなく、各ローダーのmetadataと
// Xaero mixin configが正しいjarへ入っていることまで、公開前に機械的に検査する。
tasks.register("verifyDistribution") {
    group = "verification"
    description = "全配布jarの個数・名前・loader metadata・Mixin設定を検査する"
    dependsOn(tasks.named("collectJars"))
    doLast {
        val expected = stonecutter.versions.associate { node ->
            val loader = node.project.substringAfterLast('-')
            "${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar" to loader
        }
        val javaVersions = stonecutter.versions.associate { node ->
            val loader = node.project.substringAfterLast('-')
            "${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar" to javaVersionFor(node.version)
        }
        val directory = layout.buildDirectory.dir("libs").get().asFile
        val actual = directory.listFiles { file -> file.extension == "jar" }
            ?.associateBy { it.name } ?: emptyMap()
        check(actual.keys == expected.keys) {
            "配布jarが想定と一致しません expected=${expected.keys.sorted()} actual=${actual.keys.sorted()}"
        }
        expected.forEach { (name, loader) ->
            java.util.jar.JarFile(actual.getValue(name)).use { jar ->
                // 利用者のJavaで読めないクラスが1つでも入っていれば起動しない（Java 8へ変換する1.16.5で特に）。
                // クラスファイルのmajor versionはJava 8が52で、以降1ずつ増える
                val maxMajor = 44 + javaVersions.getValue(name)
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .forEach { entry ->
                        val major = jar.getInputStream(entry).use { input ->
                            val header = input.readNBytes(8)
                            ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                        }
                        check(major <= maxMajor) { "$name: ${entry.name}がJava ${javaVersions.getValue(name)}で読めない（major $major）" }
                    }
                val mixinConfig = jar.getInputStream(jar.getEntry("xaeronav-xaero.mixins.json")).use { String(it.readBytes()) }
                check(Regex("\"JAVA_(\\d+)\"").find(mixinConfig)!!.groupValues[1].toInt() <= javaVersions.getValue(name)) {
                    "$name: mixin configのcompatibilityLevelが利用者のJavaより新しい"
                }
                check(jar.getEntry("xaeronav-xaero.mixins.json") != null) { "$name: mixin configがありません" }
                val entryNames = jar.entries().asSequence().map { it.name }.toSet()
                when (loader) {
                    "fabric" -> {
                        check(jar.getEntry("fabric.mod.json") != null) { "$name: fabric.mod.jsonがありません" }
                        check(jar.getEntry("META-INF/mods.toml") == null
                                && jar.getEntry("META-INF/neoforge.mods.toml") == null) {
                            "$name: 他loaderのmetadataが混入しています"
                        }
                        // FabricはLoomのinclude()でMETA-INF/jars/へネストしたjarのまま同梱する
                        // （@Local/@WrapOperation等mixinextrasのmixinが依存、本体には含まれない）
                        check(entryNames.any { it.startsWith("META-INF/jars/mixinextras-fabric-") }) {
                            "$name: mixinextrasが同梱されていません"
                        }
                    }
                    "forge" -> {
                        check(jar.getEntry("META-INF/mods.toml") != null) { "$name: mods.tomlがありません" }
                        check(jar.manifest.mainAttributes.getValue("MixinConfigs")
                                == "xaeronav-xaero.mixins.json") { "$name: MixinConfigs manifestが不正です" }
                        // ForgeはFG7のjarJar（またはlegacyforgeの同名機構）でMETA-INF/jarjar/へ
                        // ネストしたjarのまま同梱する（Forge本体はmixinextrasを同梱していない）。
                        // jar-in-jarの無い1.16.5は自分のパッケージへ移して直接入れる
                        check(entryNames.any {
                            it.startsWith("META-INF/jarjar/mixinextras-forge-")
                                || it.startsWith("net/prason/xaeronav/shadow/mixinextras/")
                        }) {
                            "$name: mixinextrasが同梱されていません"
                        }
                    }
                    "neoforge" -> check(jar.getEntry("META-INF/neoforge.mods.toml") != null) {
                        "$name: neoforge.mods.tomlがありません"
                    }
                    // NeoForge本体はmixinextrasを同梱済みなので、ここでの同梱検査は不要
                }
            }
        }
    }
}

// 整形の取り締まりはルートで1度だけ行う。ソースツリーは全ノードで共有しているので、
// ノードごとに走らせても同じファイルを何度も見るだけになる（spotlessは
// プロジェクトディレクトリの外にあるファイルを対象にできないので、置ける場所もここだけ）。
//
// 見る項目は「直しても議論の余地がない」ものに限る（未使用importの残骸、行末の余分な空白、
// ファイル末尾の改行漏れ）。既存の書式を丸ごと書き換える整形器は入れない——差分が全ファイルに
// 及んで意味のあるレビューができなくなる方が、崩れた書式がたまに残るより害が大きい。
//
// 注意: 無効な分岐（Stonecutterがコメント化した側）でしか使われないimportは、
// removeUnusedImportsに消される。ローダー固有のimportは必ずその分岐のゲート内側に書くこと。
spotless {
    java {
        target("src/*/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
}

// CIがノードごとのジョブ（起動スモークテスト）を組むための一覧。ノードを足しても
// ワークフロー側を書き換えずに済むよう、ノードの定義はsettings.gradle.ktsの1箇所だけにする。
tasks.register("printNodes") {
    group = "help"
    description = "全ノードを JSON 配列で出す（CIのmatrix用）"
    val nodes = stonecutter.versions.map { it.project to it.version }
    val fabricApi = fabricApiVersions(file("stonecutter.properties.toml").readText())
    doLast {
        println(nodes.joinToString(",", "[", "]") { (project, version) ->
            val loader = project.substringAfterLast('-')
            // javaは利用者の実行環境（起動確認に使うJVM）。ビルドは常にJava 21のGradleで行う
            """{"node":"$project","minecraft":"$version","loader":"$loader","java":"${javaVersionFor(version)}",""" +
                """"fabric_api":"${fabricApi["$loader.$version"] ?: "none"}"}"""
        })
    }
}

/** `[<ローダー>."<MCバージョン>"]`ごとの`deps.fabric_api`。CIがfabric-apiの配布jarを選ぶのに使う。 */
fun fabricApiVersions(toml: String): Map<String, String> {
    val result = HashMap<String, String>()
    var table: String? = null
    for (line in toml.lines()) {
        Regex("""^\[(\w+)\."([^"]+)"]""").find(line.trim())?.let { table = "${it.groupValues[1]}.${it.groupValues[2]}" }
        Regex("""^deps\.fabric_api\s*=\s*"([^"]+)"""").find(line.trim())?.let { match ->
            table?.let { result[it] = match.groupValues[1] }
        }
    }
    return result
}
