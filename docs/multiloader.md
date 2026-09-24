# 対応ターゲット（MC バージョン × ローダー）の作り

XaeroNav は 1 つのソースツリーから、対応するローダーとバージョンのぶんだけ jar を作ります。
その仕組みと、増やすときに触る場所をまとめます。

設計上の不変条件は [ADR-003: Loader, Xaero hook, and distribution contracts](architecture/003-platform-integration.md)
を参照してください。この文書は、各ノードを追加・保守する具体的な手順を扱います。

## いまのターゲット

| ノード | Minecraft | ローダー |
|---|---|---|
| `1.21.1-neoforge` | 1.21.1 | NeoForge 21.1.228+ |
| `1.21.1-fabric` | 1.21.1 | Fabric Loader 0.19.5+ / Fabric API |
| `1.21.1-forge` | 1.21.1 | Forge 52.1.16+ |
| `1.20.1-fabric` | 1.20.1 | Fabric Loader 0.19.5+ / Fabric API |
| `1.20.1-forge` | 1.20.1 | Forge 47.4.23+ |
| `1.16.5-fabric` | 1.16.5 | Fabric Loader 0.15.11+ / Fabric API 0.42.0+（Java 8） |
| `1.16.5-forge` | 1.16.5 | Forge 36.2.39+（Java 8） |

**1.20.1にNeoForgeノードは無い**（意図的）。その時点のNeoForgeはForgeとjarレベルで互換
（NeoForge自身も1.20.1ではForgeの使用を推奨）で、Xaeroも"neoforge"向けの1.20.1ビルドを
配っていない（1.20.4からしか無い）。1.20.1でNeoForgeを使うユーザーは`1.20.1-forge`のjarを使う。

ノード名は `<MC バージョン>-<ローダー>`。切り分けには [Stonecutter](https://stonecutter.kikugie.dev/)
を使っています（Architectury は入れていません）。

## ファイルの役割

| ファイル | 中身 |
|---|---|
| `settings.gradle.kts` | ノードの一覧。**ノードを増やすのはここの 1 行** |
| `stonecutter.properties.toml` | ノードごとの依存バージョン。**ノードを増やすとここにテーブルが 1 つ増える** |
| `stonecutter.gradle.kts` | 全ノード共通の入口（`buildAll` / `collectJars` / `printNodes`）と spotless |
| `build.neoforge.gradle.kts` / `build.fabric.gradle.kts` / `build.forge.gradle.kts` | ローダーごとのビルド。ローダーが増えたときだけ増える |
| `buildSrc/src/main/kotlin/xaeronav.common.gradle.kts` | 全ノード共通のビルド設定（Java toolchain・テスト・jar 名・Fletching Tableによるmixin登録）。Java版はMCバージョンで分岐（1.20.5未満は17・以降は21） |
| `src/main/java/net/prason/xaeronav/platform/` | ローダーごとの起動処理とイベント配線 |
| `src/main/resources/xaeronav.accesswidener` | Fabric専用。Mojang公式マッピングの一部ネストクラス（`RenderType.CompositeState`等）は自クラスの宣言とInnerClasses属性の宣言が食い違っており、外部から参照するには開放が要る（NeoForge/Forgeの`accesstransformer.cfg`のFabric版） |
| `build.forge-legacy.gradle.kts` | 1.20.1のForgeノード専用。1.21.1-forgeとは違うツールチェーン（`net.neoforged.moddev.legacyforge`、ForgeGradleではない） |
| `build.forge-116.gradle.kts` | 1.16.5のForgeノード専用。Architectury Loom（公式マッピングで1.16.5のForgeを扱えるのはこれだけ） |
| `buildSrc/src/main/kotlin/ForgeCoremodNames.kt` | 1.16.5-forgeの開発実行用。XaeroのcoremodにあるSRG名を開発環境の名前へ書き換える |

`gradle.properties` にあるのは MOD 自身のメタデータ（id・名前・バージョン）だけです。
Minecraft / ローダー / Xaero の版は `stonecutter.properties.toml` が唯一の情報源で、
`neoforge.mods.toml` / `fabric.mod.json` / `mods.toml`（Forge）へもそこから流し込まれます。

Xaero だけは版を2つ持ちます。`deps.xaero_worldmap` / `deps.xaero_minimap` はコンパイルと開発クライアントに使う版、
`deps.xaero_worldmap_min` / `deps.xaero_minimap_min` は MOD 定義へ書く「動く下限」です。NeoForge / Forge は任意の依存でも
下限を守らせ、古い Xaero が入っているとゲームを起動させません。コンパイル用の版を最新へ上げても、下限は動かしません。
下限を下げるときは、その版でビルドし、Xaero への参照（`javap` で見たメソッド・フィールドの型と refmap）が
今の版でのビルドと一致することを確かめます。

## Mixin一覧の生成

[Fletching Table](https://stonecutter.kikugie.dev/wiki/fletching-table) のJava annotation processorが
各ノードのコンパイル時に`@Mixin`クラスを検出し、`xaeronav-xaero.mixins.json`の`client`一覧へ
自動登録します。このJSONは生成元のテンプレートでもあり、`required`・`minVersion`・`package`・
`refmap`・`injectors`など、クラス一覧以外の設定は引き続きここで管理します。テンプレートの
`client`は空のままにし、クラス名を手で追加しないでください。追加・改名・削除はJava側の
`@Mixin`から生成結果へ反映されます。

生成は`processResources`より前に行われるため、`${mixin_compatibility_level}`の版別展開も維持されます。
Forge固有のrefmap生成やMANIFESTの`MixinConfigs`登録はFletching Tableの対象外なので、
`build.forge.gradle.kts` / `build.forge-legacy.gradle.kts`側の設定を削除しないでください。

## ノードを増やす

1. `settings.gradle.kts` の `match(...)` に 1 行足す（例: `match("1.21.5", "neoforge", "fabric")`）
2. `stonecutter.properties.toml` に `[<ローダー>."<MC バージョン>"]` のテーブルを足す
   （依存バージョンは実在するものを実際に確認してから書く。推測で書かない）
3. `./gradlew build` で全ノードのコンパイルを通す

**同じMCバージョンへローダーを1つ足すだけなら、ここまでで済む**（1.21.1-forge追加のとき）。
**新しいMCバージョンを足す場合はさらに要る**（1.20.1-fabric追加で判明。詳細は下の「版差が出る場所」）:

- `buildSrc/.../xaeronav.common.gradle.kts`のJava toolchain分岐に新しい境界が要らないか
  （MC 1.20.5未満はJava 17、以降はJava 21——2バージョン以上増えると分岐の書き方自体を見直す）
- `pack.mcmeta`の`pack_format`（各ビルドスクリプトの`packFormat`変数）
- `xaeronav-xaero.mixins.json`の`compatibilityLevel`（同上、`mixinCompatibilityLevel`変数）
- `fabric.mod.json`の`java`依存（Fabricのみ、`java_version`変数）

**Forgeのノードはmixin configの登録経路が違う。** mods.tomlの`[[mixins]]`を読むのはNeoForgeだけで、
Forgeは版に関わらずMixin本体がjarのMANIFESTの`MixinConfigs`しか見ない。欠けるとXaero連携が本番で
黙って1本も当たらない（`required=false`なので落ちもしない）。ビルド後は
`unzip -p <jar> META-INF/MANIFEST.MF`で`MixinConfigs`を確かめる。

- 配布jar: `jar`タスクの`manifest.attributes("MixinConfigs" to ...)`（jarJarの出力にも引き継がれる）
- 開発実行: MODをクラスディレクトリから読むのでMANIFESTが無い。ForgeGradleは`args("--mixin.config", ...)`、
  ModDevGradle legacyforgeは`mixin { config(...) }`で渡す
- 本番がSRG名で動く1.20.x以前のForgeはrefmapも要る（`mixin { add(sourceSets["main"], ...) }`）。
  公式マッピングで動く1.21.1-forgeには要らない

CI は `printNodes` からノード一覧を作るので、ワークフローの書き換えは要りません
（`runtime`ジョブをPRで正典ノードだけに絞る判定はファイルパスベースなので、`stonecutter.properties.toml`・
`settings.gradle.kts`・`mixin/`のいずれかを触るPRなら自動で全ノードに広がります）。

## 版差が出る場所

`1.20.1-fabric` を足したときに実際に踏んだ版差（1.20.1 ⇔ 1.21.1）。1.21.5 の
`RenderPipeline` / `GpuBuffer` 全面リワークより手前でも、これだけの差がある。

- **GUI画面の基底クラス**（`client/gui/XaeroNavConfigScreen`）。1.21.1の`OptionsSubScreen`は
  `net.minecraft.client.gui.screens.options`パッケージ・`addOptions()`フックを持つが、1.20.1の
  同名クラスは`net.minecraft.client.gui.screens`直下にあり、`addOptions()`が無く`init()`を
  自分で書く必要がある（`OptionsList`の生成・Doneボタンの配置まで自前）
- **頂点バッファAPI**（`client/PathRenderer`の`vertex`/`line`）。1.21.1は`addVertex(pose,x,y,z)`
  を起点にした新API（endVertex不要）、1.20.1は`vertex(x,y,z)`起点で`color`/`normal`を
  チェーンし最後に`endVertex()`で確定する旧API
- **`RenderType.CompositeState` / `RenderStateShard.LineStateShard`のアクセス**
  （`client/NavRenderTypes`）。両バージョンとも自クラスファイルの宣言は`public`だが、
  外側のクラス（`RenderType`/`RenderStateShard`）が持つInnerClasses属性上の宣言は`protected`——
  javacは後者を見て解決するため、ゲートではなくアクセス開放が要る。NeoForge/Forgeは
  `accesstransformer.cfg`で開放しているのと同じ話が、Fabricでは`xaeronav.accesswidener`
  （`accessible class ...`）になる
- `mixin/xaero/` が触る Xaero 側の内部（`CustomRenderTypes` / `MapRenderHelper` / `GuiMap#render` の
  `endBatch()` の ordinal）。ここは **Minecraft ではなく Xaero の更新で動きます**

### JDK自体のバージョン差はゲートしない

1.20.1はJava 17必須（1.21.1はJava 21）。`Math.clamp`・`List#getLast()`等のJDK21で追加された
標準ライブラリAPIは、`//?`で分岐せず**自前の実装に置き換えて両バージョンで同じコードを使う**
（`util/MathSupport`、テストコードの`list.get(list.size() - 1)`など）。バージョンゲートは
Minecraft自体のAPI差にだけ使う。

## 1.16.5（Java 8）

ソースは他のノードと同じくJava 21の構文で書き、Java 21でコンパイルしてから
[JvmDowngrader](https://github.com/unimined/JvmDowngrader)でJava 8のクラスファイルへ変換する。
配布jarは変換後のもの（`java8Jar`、分類子なし）で、変換前は`-java21`の分類子付きで残る。

- **mixin configの`compatibilityLevel`は配布jarの中だけ`JAVA_8`へ書き換える**（`registerJava8Jar`）。
  開発実行はJava 21のままのクラスを読むので、開発側の値はJava 11以降の機能（NESTING）を許す値にしておく必要がある。
  `verifyDistribution`が、どのjarにも利用者のJavaで読めないクラスや`compatibilityLevel`が無いことを検査する
- **Forge 1.16.5はMixinExtrasを同梱せず、jar-in-jarも無い**。`mixinextras-common`を
  `net.prason.xaeronav.shadow.mixinextras`へ移して配布jarへ入れ、mixin configのplugin
  （`mixin/MixinExtrasBootstrapPlugin`）で起動する。pluginの行は1.16.5-forgeの`processResources`だけが足す
- **本番のForge 1.16.5はSRG名で動く**。refmapはArchitectury LoomのMixin APで作る（`useLegacyMixinAp`）
- **Xaeroの1.16.5 Forge版はcoremod（JavaScript）の中にSRG名を直書きしている**。本番では問題ないが、
  Mojang名で動く開発環境では`NoClassDefFoundError: ToggleableKeyBinding`で起動しない。
  `fixXaeroCoremods`（`runClient`の前に走る）がLoomの変換済みjarの中のcoremodを開発環境の名前へ書き換える
- 1.16.5のXaero 1.46.0/26.5.0は`GuiMap#render`・`MinimapFBORenderer#renderChunksToFBO`の先頭付近で
  別のバッファを1回余分に`endBatch()`する。mixinの注入先のordinalが1つずれる（`//? if <1.17`）
- 1.16.5が既定で使うLWJGLは新しいmacOSでウィンドウを作れないので、開発実行だけLWJGL 3.3.3へ上げている。
  配布jarと利用者の環境には関係しない

## 守る決まり

### `pathfinding/` に `//?` を書かない（例外は vanilla API のシグネチャ差だけ）

経路探索のテストは正典ノード（`stonecutter.properties.toml` の `canonical_test_node`）でしか
走りません。`pathfinding/` に版分岐が入ると、正典ノードのテストが他ノードのバグを見逃します。
版分岐が要るなら、その差を吸収する層を `client/` か `platform/` 側に作ってください。

**唯一の例外**: vanilla APIの**呼び出し方（シグネチャ）そのものが版で違う**が、**意味は変わらない**
場合。1.20.1対応で2箇所だけ実例が出た——
`pathfinding/world/CellData.java`の`BlockStateBase#isPathfindable`（1.20.1は
`(BlockGetter, BlockPos, PathComputationType)`という旧シグネチャを取る。levelを見ない判定なので
空のプローブ値を渡せば同じ)と、`pathfinding/world/ChunkView.java`のエンチャント効率レベル取得
（1.20.1はレジストリ経由の`Holder<Enchantment>`ではなく`Enchantments`直下の静的フィールドを
直接渡す旧モデル）。**ロジックが分岐するわけではない**ので、正典ノードのテストが検証している
中身は変わらない。判断に迷ったら、まずJDK差と同じくポータブルな書き方で両バージョンとも
同じコードにできないかを先に検討すること（`Inventory#contains(Predicate)`が1.20.1に無い件は
手書きループに置き換えてゲート無しで解決した——`ChunkView.hasItem`）。

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

- `build`ジョブ（ノードごとのmatrix、`:<node>:build`）— コンパイル・正典ノードでのテスト・spotless
- `runtime`ジョブ（ノードごとに実際にクライアントを起動してワールドへ入る、`headlesshq/mc-runtime-test`）。
  通常のPRでは正典ノードだけに絞り、mainへのpush・週次スケジュール・ノード定義やmixinを触ったPRでは
  全ノードへ広がる（ノードが増えてもruntimeジョブの総数が線形に膨らまないようにするため）
- 起動ログに XaeroNav の mixin 適用失敗が無いこと
- `server`ジョブ（Forgeノードの配布jarを専用サーバーへ入れても起動を妨げないこと）

`runtime`と`server`は利用者と同じJava（`printNodes`の`java`。1.16.5は8、1.20.1は17、それ以外は21）で
Minecraftを動かす。Gradle自体はどのノードでもJava 21で動く。

3 つ目が要るのは、`xaeronav-xaero.mixins.json` が `required=false` だからです。注入先が変わっても
例外は出ず、ユーザーには「地図に線が出ない」としか見えません。ログにだけ出るので、CI が読みます。

実行中の状態は `/xaeronav debug hooks` で確認できます。世界地図を開いている間に描画の注入点を
一度も通らなかった場合は、HUD にも警告が出ます。
