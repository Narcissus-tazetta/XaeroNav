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

// jar名にノードを含める。ノードが増えると build/libs へ同名のjarが並んでしまい、
// どれがどのローダー/バージョン向けか配布時に判別できなくなる。
base {
    archivesName = "${modProperty("mod_id")}-$loader-$minecraftVersion"
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

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("6.1.3")

            // 実機の保存データで60万ノードの探索を回すテストは1本あたり8秒前後かかる。
            // 手元で回し続ける既定の`test`からは外し、`slowTest`（`check`が依存）に任せる
            targets.all {
                testTask.configure {
                    useJUnitPlatform { excludeTags("slow") }
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
}

tasks.named("check") { dependsOn(slowTest) }

// テストは正典ノードでだけ実行する。経路探索コアはローダーにもMCバージョンにも依存せず
// （`pathfinding/`に`//?`を書かない鉄則）、どのノードで回しても同じ結果になるので、
// 全ノードで回すのはCIの時間を丸ごと倍にするだけになる。コンパイルは全ノードで走る。
val canonicalNode = node.properties.get<String>("canonical_test_node")
val isCanonicalNode = node.current.project == canonicalNode
tasks.withType<Test>().configureEach {
    onlyIf("正典ノード($canonicalNode)でのみ実行する") { isCanonicalNode }

    // ノードのプロジェクトディレクトリはversions/<ノード>だが、テストが読むソースツリー
    // （言語ファイル・実機の保存データ）はリポジトリのルートにある
    workingDir = rootProject.projectDir
}
