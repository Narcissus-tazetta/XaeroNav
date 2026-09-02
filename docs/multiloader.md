# 対応ターゲット（MC バージョン × ローダー）の作り

XaeroNav は 1 つのソースツリーから、対応するローダーとバージョンのぶんだけ jar を作ります。
その仕組みと、増やすときに触る場所をまとめます。

## いまのターゲット

| ノード | Minecraft | ローダー |
|---|---|---|
| `1.21.1-neoforge` | 1.21.1 | NeoForge 21.1.228+ |
| `1.21.1-fabric` | 1.21.1 | Fabric Loader 0.19.5+ / Fabric API |

ノード名は `<MC バージョン>-<ローダー>`。切り分けには [Stonecutter](https://stonecutter.kikugie.dev/)
を使っています（Architectury は入れていません）。

## ファイルの役割

| ファイル | 中身 |
|---|---|
| `settings.gradle.kts` | ノードの一覧。**ノードを増やすのはここの 1 行** |
| `stonecutter.properties.toml` | ノードごとの依存バージョン。**ノードを増やすとここにテーブルが 1 つ増える** |
| `stonecutter.gradle.kts` | 全ノード共通の入口（`buildAll` / `collectJars` / `printNodes`）と spotless |
| `build.neoforge.gradle.kts` / `build.fabric.gradle.kts` | ローダーごとのビルド。ローダーが増えたときだけ増える |
| `buildSrc/src/main/kotlin/xaeronav.common.gradle.kts` | 全ノード共通のビルド設定（Java 21・テスト・jar 名） |
| `src/main/java/net/prason/xaeronav/platform/` | ローダーごとの起動処理とイベント配線 |

`gradle.properties` にあるのは MOD 自身のメタデータ（id・名前・バージョン）だけです。
Minecraft / ローダー / Xaero の版は `stonecutter.properties.toml` が唯一の情報源で、
`neoforge.mods.toml` と `fabric.mod.json` へもそこから流し込まれます。

## ノードを増やす

1. `settings.gradle.kts` の `match(...)` に 1 行足す（例: `match("1.21.5", "neoforge", "fabric")`）
2. `stonecutter.properties.toml` に `[<ローダー>."<MC バージョン>"]` のテーブルを足す
3. `./gradlew build` で全ノードのコンパイルを通す

CI は `printNodes` からノード一覧を作るので、ワークフローの書き換えは要りません。

## 版差が出る場所

ローダー非依存・バージョン非依存のコード（`pathfinding/` 49 ファイルと全テスト）は、
ノードを増やしても 1 行も変わりません。版差が出るのは描画まわりだけです。

- `client/PathRenderer` `client/NavRenderTypes` `client/MapPathOverlay` `client/NavHud` の blaze3d 呼び出し
  （1.21.5 で `RenderPipeline` / `GpuBuffer` へ全面的に変わっています）
- `mixin/xaero/` が触る Xaero 側の内部（`CustomRenderTypes` / `MapRenderHelper` / `GuiMap#render` の
  `endBatch()` の ordinal）。ここは **Minecraft ではなく Xaero の更新で動きます**

## 守る決まり

### `pathfinding/` に `//?` を書かない

経路探索のテストは正典ノード（`stonecutter.properties.toml` の `canonical_test_node`）でしか
走りません。`pathfinding/` に版分岐が入ると、正典ノードのテストが他ノードのバグを見逃します。
版分岐が要るなら、その差を吸収する層を `client/` か `platform/` 側に作ってください。

### ローダー固有の import はゲートの内側に書く

spotless の `removeUnusedImports` は、いま無効な分岐でしか使われない import を消します。
無効な分岐は Stonecutter がコメントにするので、ゲートの内側に書いてあれば触られません。

```java
//? neoforge {
import net.neoforged.fml.ModList;
//?} fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?}
```

ファイルまるごとローダー固有なら、`package` 行の後ろから末尾までを 1 つのゲートで囲みます
（`platform/fabric/FabricEntry.java` がその形）。

### コミット前に有効ノードを戻す

Stonecutter は有効なノードに合わせて `src/` を書き換えます。別のノードを有効にしたまま
差分を取ると、全ファイルが動いて見えます。

```bash
./gradlew "Reset active project"
```

## CI が見ているもの

- `./gradlew build collectJars` — 全ノードのコンパイル、正典ノードでのテスト、spotless
- ノードごとに実際にクライアントを起動してワールドへ入る（`headlesshq/mc-runtime-test`）
- 起動ログに XaeroNav の mixin 適用失敗が無いこと

3 つ目が要るのは、`xaeronav-xaero.mixins.json` が `required=false` だからです。注入先が変わっても
例外は出ず、ユーザーには「地図に線が出ない」としか見えません。ログにだけ出るので、CI が読みます。

実行中の状態は `/xaeronav debug hooks` で確認できます。世界地図を開いている間に描画の注入点を
一度も通らなかった場合は、HUD にも警告が出ます。
