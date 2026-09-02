plugins {
    id("dev.kikugie.stonecutter")
    id("com.diffplug.spotless") version "8.10.1"
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
    // `//? if neoforge {` / `//? if fabric {` が切り替わる。
    constants.match(current.project.substringAfterLast('-'), "neoforge", "fabric")
}

// 全ノードをまとめて回すための入口。ノードを増やしてもCIの記述は変わらない。
tasks.register("buildAll") {
    group = "build"
    description = "すべてのノード（MCバージョン×ローダー）をビルドする"
    dependsOn(stonecutter.tasks.named("build"))
}

tasks.register<Copy>("collectJars") {
    group = "build"
    description = "全ノードの配布jarをルートのbuild/libsへ集める"
    dependsOn(tasks.named("buildAll"))
    from(stonecutter.versions.map { layout.projectDirectory.dir("versions/${it.project}/build/libs") }) {
        include("*.jar")
    }
    into(layout.buildDirectory.dir("libs"))
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
    doLast {
        println(nodes.joinToString(",", "[", "]") { (project, version) ->
            """{"node":"$project","minecraft":"$version","loader":"${project.substringAfterLast('-')}"}"""
        })
    }
}
