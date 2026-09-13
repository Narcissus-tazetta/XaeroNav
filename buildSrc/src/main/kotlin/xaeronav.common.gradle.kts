import dev.kikugie.stonecutter.build.StonecutterBuildExtension

plugins {
    id("java-library")
}

// Stonecutterは各ノードへ自分のビルドプラグインを先に当てるので、ここで参照できる。
val node = extensions.getByType<StonecutterBuildExtension>()
val loader = node.current.project.substringAfterLast('-')
val minecraftVersion = node.current.version

group = modProperty("mod_group_id")
version = stampedModVersion()

// jar名は `<mod_id>-<mod_version>-<ローダー>-<MCバージョン>[-<gitハッシュ>].jar`。
// ローダー/MCバージョンをファイル名に含めないと build/libs へ同名のjarが並び、
// どれがどのノード向けか配布時に判別できなくなる（ノードごとに build/libs は別）。
base {
    archivesName = modProperty("mod_id")
}

// MojangがMC 1.20.5以降でJava 21を要求するようになった境界線。今のところ1.20.1と1.21.1しか
// ノードが無いのでこの1行で足りるが、1.20.5以降の別バージョンを増やすときは書き直しが要る
val javaVersion = if (minecraftVersion.startsWith("1.20.")) 17 else 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

// 非推奨APIの発生元を通常ログへ必ず出す。まとめの「一部で使用」だけでは更新対象を特定できない。
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

repositories {
    mavenCentral()
    maven("https://chocolateminecraft.com/maven") { name = "Xaero's Maven" }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("6.1.3")

            // 実機の保存データで60万ノードの探索を回すテストは1本あたり8秒前後かかる。
            // 手元で回し続ける既定の`test`からは外し、`slowTest`（`check`が依存）に任せる
            targets.all {
                testTask.configure {
                    useJUnitPlatform { excludeTags("slow", "bench") }
                }
            }
        }
    }
}

/**
 * `@Tag("slow")`の付いたテストだけを回す。実機ジ・エンドの地形で「規模が大きいときにだけ
 * 現れる穴」を見張るもので、合成地形では構造的に再現できない。
 */
val slowTest by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "実機のワールド保存データを使う重い経路探索テストを回す"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    // ネザーのフィクスチャは体積のほとんどが固体で、512ブロック四方でも1690万セルになる
    // （FakeCellsは空気を持たない疎な表なので、現世の同じ面積とは桁が違う）。既定のヒープでは
    // 地形を読み込む途中でOutOfMemoryErrorになる
    maxHeapSize = "3g"
    // テストクラスごとにJVMを作り直す。1つのJVMで回すと、クラスごとに読む大きな地形が
    // 積み上がってヒープを使い切る（実際にテスト結果を1件も残さずJVMごと落ちた）。
    // 起動のぶんは遅くなるが、重いテストは元々1本あたり数十秒かかる
    forkEvery = 1
    // 直列だと重い3本（Nether{WideRoute,LiveWalk,DetourBreakdown}Test、合計約14分）が
    // 積み上がって全体で30分超になる（CI実測）。クラスはJVM単位で独立しているので並列化して
    // 素直に効く。1コアはGradle本体・他タスクに残す。3g(maxHeapSize)×並列数ぶんのメモリが
    // 要るので上限4に留める（GitHub Actions既定ランナーの4vCPU/16GBで3並列なら収まる）
    maxParallelForks = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
}

tasks.named("check") { dependsOn(slowTest) }

/**
 * `@Tag("bench")`の付いた計測を回す。番人ではないので`check`からは外してある——
 * 判定を持たない計測をCIに載せても、赤にならないぶん誰も見ない。
 */
val bench by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "経路探索の速度・質を計測する（判定なし）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("bench") }
    // 理由はslowTestと同じ
    maxHeapSize = "3g"
    forkEvery = 1
    systemProperty("xaeronav.profileOut",
            layout.buildDirectory.dir("bench").get().asFile.absolutePath)
}

// テストは正典ノードでだけ実行する。経路探索コアはローダーにもMCバージョンにも依存せず
// （`pathfinding/`に`//?`を書かない鉄則）、どのノードで回しても同じ結果になるので、
// 全ノードで回すのはCIの時間を丸ごと倍にするだけになる。コンパイルは全ノードで走る。
val canonicalNode = node.properties.get<String>("canonical_test_node")
val isCanonicalNode = node.current.project == canonicalNode
tasks.withType<Test>().configureEach {
    onlyIf("正典ノード($canonicalNode)でのみ実行する") { isCanonicalNode }

    // テストは使い捨てのディレクトリで走らせる。ここをリポジトリのルートにすると、
    // クラスパスに載っているMinecraftのlog4j設定がルート直下の`logs/`へ書き出し、
    // テストを回すたびにローテートされたログが溜まり続ける
    workingDir = layout.buildDirectory.dir("test-run").get().asFile
    doFirst {
        workingDir.mkdirs()
    }

    // ソースツリー（言語ファイル等）を読むテストのための基点。作業ディレクトリからの
    // 相対パスで書くと、上のとおり作業ディレクトリを動かした時点で壊れる
    systemProperty("xaeronav.projectRoot", rootProject.projectDir.absolutePath)
}

// 配布jarのファイル名にはバージョン（+gitの短縮ハッシュ）が入るので、ビルドのたびに
// 別名のjarが増える。Gradleが把握しているのはタスクの出力"ファイル"1つだけなので、
// 隣に残った過去の世代は誰も消さず、build/libsに溜まり続ける。
//
// 消すのは「同じ成果物の、違うバージョン」だけに限る。同じバージョンの別種
// （loomが作る -dev や -sources）は残す。
tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion = archiveVersionFor(loader, minecraftVersion)

    doFirst {
        val directory = destinationDirectory.get().asFile
        val currentVersion = archiveVersion.get()
        val prefix = archiveBaseName.get() + "-"
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".jar")
                    && !file.name.contains(currentVersion)
        }?.forEach { it.delete() }
    }
}
