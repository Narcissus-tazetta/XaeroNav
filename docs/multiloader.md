# 対応ターゲット（MC バージョン × ローダー）の作り

XaeroNav は 1 つのソースツリーから、対応するローダーとバージョンのぶんだけ jar を作ります。
その仕組みと、増やすときに触る場所をまとめます。

設計上の不変条件は [ADR-003: Loader, Xaero hook, and distribution contracts](architecture/003-platform-integration.md)
を参照してください。この文書は、各ノードを追加・保守する具体的な手順を扱います。

## いまのターゲット

| ノード | Minecraft | ローダー |
|---|---|---|
| `26.3-fabric` | 26.3 | Fabric Loader 0.19.0+ / Fabric API 0.161.0+ |
| `26.3-forge` | 26.3 | Forge 66.0.9+ |
| `26.2-fabric` | 26.2 | Fabric Loader 0.19.0+ / Fabric API 0.161.0+ |
| `26.2-forge` | 26.2 | Forge 65.1.3+ |
| `26.2-neoforge` | 26.2 | NeoForge 26.2.0.88+ |
| `26.1.2-fabric` | 26.1.2 | Fabric Loader 0.19.0+ / Fabric API 0.155.3+ |
| `26.1.2-forge` | 26.1.2 | Forge 64.1.3+ |
| `26.1.2-neoforge` | 26.1.2 | NeoForge 26.1.2.112+ |
| `1.21.11-fabric` | 1.21.11 | Fabric Loader 0.17.3+ / Fabric API 0.141.6+ |
| `1.21.11-forge` | 1.21.11 | Forge 61.2.1+ |
| `1.21.11-neoforge` | 1.21.11 | NeoForge 21.11.45+ |
| `1.21.10-fabric` | 1.21.10 | Fabric Loader 0.17.0+ / Fabric API 0.138.4+ |
| `1.21.10-forge` | 1.21.10 | Forge 60.1.15+ |
| `1.21.10-neoforge` | 1.21.10 | NeoForge 21.10.64+ |
| `1.21.8-fabric` | 1.21.8 | Fabric Loader 0.16.13+ / Fabric API 0.136.1+ |
| `1.21.8-forge` | 1.21.8 | Forge 58.1.22+ |
| `1.21.8-neoforge` | 1.21.8 | NeoForge 21.8.54+ |
| `1.21.5-fabric` | 1.21.5 | Fabric Loader 0.16.10+ / Fabric API 0.128.2+ |
| `1.21.5-forge` | 1.21.5 | Forge 55+ |
| `1.21.5-neoforge` | 1.21.5 | NeoForge 21.5+ |
| `1.21.4-fabric` | 1.21.4 | Fabric Loader 0.16.9+ / Fabric API 0.119.4+ |
| `1.21.4-forge` | 1.21.4 | Forge 54+ |
| `1.21.4-neoforge` | 1.21.4 | NeoForge 21.4+ |
| `1.21.1-neoforge` | 1.21.1 | NeoForge 21.1.228+ |
| `1.21.1-fabric` | 1.21.1 | Fabric Loader 0.19.5+ / Fabric API |
| `1.21.1-forge` | 1.21.1 | Forge 52.1.16+ |
| `1.20.4-neoforge` | 1.20.4 | NeoForge 20.4+ |
| `1.20.4-fabric` | 1.20.4 | Fabric Loader 0.15.11+ / Fabric API 0.97.3+ |
| `1.20.4-forge` | 1.20.4 | Forge 49+ |
| `1.20.1-fabric` | 1.20.1 | Fabric Loader 0.19.5+ / Fabric API |
| `1.20.1-forge` | 1.20.1 | Forge 47.4.23+ |
| `1.19.2-fabric` | 1.19.2 | Fabric Loader 0.15.11+ / Fabric API 0.77.0+ |
| `1.19.2-forge` | 1.19.2 | Forge 43+ |
| `1.18.2-fabric` | 1.18.2 | Fabric Loader 0.15.11+ / Fabric API 0.77.0+ |
| `1.18.2-forge` | 1.18.2 | Forge 40+ |
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
| `build.forge-legacy.gradle.kts` | 1.18.2・1.19.2・1.20.1のForgeノード。1.21.1-forgeとは違うツールチェーン（`net.neoforged.moddev.legacyforge`、ForgeGradleではない） |
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
- `pack.mcmeta`の形式（`buildSrc`の`packFormatFor`。未登録の版はビルドが止まる。クライアントjarの`version.json`の`pack_version`から足す）
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

## 1.18.2・1.19.2

`>=1.17`のゲートは、実際には「1.16.5より後」ではなく個々のAPIの導入版で分かれる。1.18.2・1.19.2を足すときに
境界を実際の版へ振り直した（1.16.5と1.20.1の側の真偽は変えていない）。

| 境界 | ゲートしているもの |
|---|---|
| 1.19 | `Component.translatable/literal`（`TextCompat`）、Forge 41+の`RegisterKeyMappingsEvent`・`ConfigScreenHandler`・`RegisterGuiOverlaysEvent`・`ClientPlayerNetworkEvent.LoggingIn/Out`・`EnchantmentHelper.getTagEnchantmentLevel`、Fabric APIのクライアントコマンドv2 |
| 1.19.3 | `OptionInstance`（設定画面はそれ以前は1.16.5と同じ独自の`Screen`）、`BuiltInRegistries`、`org.joml`、`SoundEvents`のHolder化 |
| 1.19.4 | `BlockPos.containing` |
| 1.20 | `GuiGraphics`、`RenderType.debugQuads`・`RenderType.create`の公開、`BlockState.canBeReplaced()`、`DoorBlock.type()`、`CommandSourceStack.sendSuccess(Supplier, boolean)`、`BlockPosArgument.getBlockPos`、Xaeroの`endBatch()`のordinal |

- **Xaeroの`endBatch()`のordinalは1.18.2・1.19.2で1**（1.20+は0）。`GuiMap#render`と`renderChunksToFBO`の先頭に前フレームの取り残しを
  flushする呼び出しがもう1回ある（1.16.5と同じ）。Xaeroのjarのバイトコードを1.18.2・1.19.2・1.20.1で並べて確かめた。
  ordinal 0のままだと例外にならず別のバッファへ描いてしまい、経路が地図に出ない。
- **`RenderType.create`（7引数）は1.20より前ではprivate**。全ノード共通の`xaeronav.accesswidener`へ足すと、メソッドの形が違う
  1.16.5・1.21.xでAWの適用が失敗するので、1.18・1.19のFabricノードだけ`build.fabric.gradle.kts`が開放を足したAWを生成して
  jarへ入れる（ForgeはATが`RenderType *`を開放済み）。`RenderStateShard`の定数も、1.20より前のFabric APIは開放していないので
  `NavRenderTypes`の内部クラス（`RenderStateShard`のサブクラス）から読む。
- 1.18.2にはFabric APIのクライアントコマンドv2が無く（v1の`ClientCommandManager.DISPATCHER`）、Forge 40には
  `RegisterKeyMappingsEvent`・`ConfigScreenHandler`・`RegisterGuiOverlaysEvent`・`ClientPlayerNetworkEvent.LoggingIn`が無い
  （`ClientRegistry`・`ConfigGuiHandler`・`RenderGameOverlayEvent.Post`・`LoggedInEvent`を使う）。
- `DiggableBlocks`は、1.19で入った洞窟の置換タグ（`*_carver_replaceables`）・`#sculk_replaceable`・`SCULK`・`MANGROVE_ROOTS`が
  1.18.2に無いので、石・土・砂・テラコッタ・ナイリウム等のタグと明示したブロックで同じ範囲を近似している。

## 1.20.4

Fabric・Forge・NeoForgeの3ローダーを持つ。1.20.1とゲーム側のAPIは近いが、ビルドとローダーAPIには次の差がある。

- `OptionsList`のコンストラクタは1.20.2から行高の引数を取らない。設定画面はこの境界で分岐する
- Forge 49のclient tickは`ClientTickEvent.Post`。1.20.1以前の`phase == END`判定は不要
- Forge 1.20.4はFG7でビルドするが、本番はまだSRG名で動く。MixinExtrasをjar-in-jarした後のjarを
  Renamer GradleでSRGへ変換し、その出力だけを配布する。Xaeroの`GuiMap#keyPressed`もSRG実名を注入先にする
  - 変換タスク（`renameJarJar`）のmapはRenamerの既定（`renamer.mappings`）に任せる。手で足すと2ファイルになって拒否される
  - 注入先の文字列をSRGへ引くrefmapは、annotation processorにmixinextras-commonも載せないと`@WrapOperation`分が空になる。
    名前もmixin configの`"refmap"`に揃える（`xaeronav.refmap.json`）。`verifyDistribution`が中身まで見る
  - 開発実行では、Xaero同梱refmapのSRG名をnamedへ読み替えるファイルをMixin 0.8.5が読める`srg`形式で渡す
    （Renamerが渡す`tsrg`は黙って無視される）
- NeoForge 20.4は21.xより古いAPIを使う。config登録は`ModLoadingContext`、設定画面は
  `ConfigScreenHandler.ConfigScreenFactory`、client tickは旧`TickEvent`、`ModConfigSpec#defineListAllowEmpty`は3引数
- NeoForge 20.4のFMLはMOD定義を`META-INF/mods.toml`からしか読まない（`neoforge.mods.toml`は20.5から）。
  jarには`mods.toml`の名前で入れる。名前を間違えるとMODごと読み込まれない（mixinの`[[mixins]]`は20.4でも効く）
- `pack.mcmeta`のresource pack formatは22

## 1.21.4

Fabric・Forge・NeoForgeの3ローダーを持つ。1.21.1と1.21.5の間にあたり、`>=1.21.5`でゲートしていたAPIの一部は実際には
1.21.2で変わっていたので、境界を1.21.2へ振り直した（1.21.1・1.21.5・1.21.11の真偽は変えていない）。

| 境界 | ゲートしているもの |
|---|---|
| 1.21.2 | `LevelHeightAccessor`の高さ（`getMinY`・`getMaxY`・`getMinSectionY`）、`RegistryAccess#lookupOrThrow`とHolderの取り方（エンチャントの効率）、`Registry#getValue`（`Registry#get`はOptionalを返す）、Fabricの`CommandSourceStack`の組み立て（`LocalPlayer#createCommandSourceStack`が無くなった）、Forgeのワールド描画の入口 |
| 1.21.5 | 描画の`RenderPipeline`、Forgeの`LevelRenderer`のラムダの引数 |

- **Forge 54には`RenderLevelStageEvent`が無い**。1.21.2で描画がフレームグラフになったため。1.21.5と同じく`ForgeLevelRendererMixin`が
  `LevelRenderer`のメインパスのラムダの末尾へ注入する。ラムダ（`lambda$addMainPass$1`）の引数は1.21.4と1.21.5で違うので、
  シグネチャを版で分けている。1.21.4ではmodelViewをRenderSystem側が描画時に掛けるので、渡す`PoseStack`は単位行列のまま
  （1.21.5はmodelViewを積んで渡す）。積むと二重に回って線が画面外へ出る。NeoForge 21.4には`RenderLevelStageEvent`がある。
- 描画は1.21.1と同じ`RenderSystem`の経路（`RenderPipeline`は1.21.5から）。
- NeoForge 21.4のFMLは`META-INF/neoforge.mods.toml`を読む（`mods.toml`限定なのは20.4だけ）。
- `pack.mcmeta`のresource pack formatは46。
- Xaero（World Map・Minimap）は3ローダーとも1.21.4専用のjarがある。`GuiMap#render`・`MinimapFBORenderer#renderChunksToFBO`の
  `endBatch()`のordinalと`@Local`の変数名は1.21.1と同じ（バイトコードで確認）。

## 1つのjarを複数のMinecraftバージョンで使う

1.21.1のNeoForge版は1.21でも、1.20.1のFabric版・Forge版は1.20でも、同じjarで動く。
対応表は`minecraftCompatFor`（`buildSrc/src/main/kotlin/XaeroNavBuild.kt`）で、値は下側のバージョン。
これがMOD定義のMinecraft範囲（`minecraft_range_fabric` / `minecraft_range_maven`）と、
Modrinth・CurseForgeへ付ける対応バージョンの両方を決める。

- **付けられるのは、現行のXaeroのjarがその版で動くローダーだけ。** Fabric版のMinimapは1.21.1ちょうど、
  Forge版は1.21.1のForge 52以上を要求するので、Fabric・Forgeの1.21には付けていない。
- ローダー側の下限（`neoforge_range` / `forge_loader_range` / `fabric_api_range`）は、下側のバージョンで
  実際に動いた版まで下げる。`fabric_api_range`は開発に使う`fabric_api`とは別のキー。
- 下側のバージョンには古いローダーが乗る。**その版のAPIで足りるか**を実機で確かめること。
  Forge 46（1.20.0）にはForge 47にある次の2つが無く、`<1.21`のノードは46に揃えてある。
  - `@Mod`クラスのコンストラクタへの`FMLJavaModLoadingContext`の注入（引数なしで`get()`を使う）
  - `ForgeConfigSpec.Builder#defineListAllowEmpty(String, List, Predicate)`（`List<String>`と`Supplier`を取る版を使う）
- NeoForge 21.0.xは`@EventBusSubscriber`の購読先のバスを自動で選ばないので、MODバスのイベントは
  `modEventBus.addListener`で登録する。

## 26.1・26.2・26.3

難読化されていない世代（Java 25）。ビルドの土台から変わる。NeoForge 26.3は安定版が出ていない（betaのみ）ので足していない。

- Java 25で動く（`javaVersionFor`）。Mixinの`compatibilityLevel`はForgeが`JAVA_25`を知らないので21で頭打ちにする。
  パック形式は公式クライアントの`version.json`の値（26.1.2はresource 84 / data 101、26.2は88 / 107、26.3は97 / 121）。
- Fabricは`build.fabric-26.gradle.kts`（リマップしない`net.fabricmc.fabric-loom`）。マッピング・`mod*`依存・`remapJar`が無い。
  キー登録は`KeyMappingHelper`、世界の描画は`LevelRenderEvents.END_MAIN`（`poseStack()`）。
- ForgeはForgeGradle 7.0.40以降が要る（それ以前はATツールが26.xのクライアントjarで落ちる）。ATは26.xでは当てない
  （開放していたRenderStateShard・RenderTypeの構造が無い）。XaeroのMaven上のjarは`META-INF/jarjar/metadata.json`だけを持ち
  入れ子のxaerolibが無いので、開発実行へ載せるものは`stripXaeroJarJar`でmetadataを外す。
  `ModList`はstatic（26.1）。`PassDefinition#extracts`の第3引数は`LevelRenderState`（26.3）。
- NeoForgeはModDevGradle 2.0.148以降が要る（2.0.146では26.2のMinecraftの再コンパイルが落ちる）。
- 26.1: `GuiGraphics`は`GuiGraphicsExtractor`、`drawCenteredString`は`centeredText`、`Screen#render`は`extractRenderState`
  （XaeroのGuiMapへの注入先も）、深度の設定は`DepthStencilState`、`ChunkPos.asLong`は`pack`、`displayClientMessage`は
  `sendOverlayMessage`・`sendSystemMessage`。`LevelRenderState`は`renderer.state.level`へ移った。
- 26.2: `MultiBufferSource`が無くなり、`NavBuffers`が`StagedVertexBuffer`の上に`getBuffer`→`endBatch`の流れを作る。
  `Minecraft#screen`・`#setScreen`は`gui`の下へ、`Options#hideGui`は`Hud#isHidden`、`GameRenderer#getMainCamera`は
  `mainCamera`（`ClientCompat`）。石炭・ラピス・レッドストーン・ダイヤ・エメラルドの鉱石タグの定数が消えた（タグ自体は残る）。
- 26.3: GPUの抽象が`com.mojang.renderpearl`へ移り、GLFWの代わりにSDLが入った（キー定数は`InputConstants`）。
  `PreparedRenderType#drawFromBuffer`はレンダーパスを受け取るので、`NavBuffers`が自分で開く。
  洞窟が置換タグではなく`#uncarvable`（bedrockだけ）を使うようになったので、`DiggableBlocks`の自然地形は
  石・土・草・泥・苔・砂・テラコッタ・ナイリウムのタグと明示したブロックで近似している。
- 手元の確認: `-Pxaeronav.quickPlay=<ワールド名>`でタイトル画面を飛ばして既存のワールドへ入れる
  （`options.txt`が無いと最初のアクセシビリティ画面で止まる）。

## 1.21.8・1.21.10

- 3ローダーとも専用ノードを持ち、Java 21で動く。パック形式は公式クライアントの`version.json`で確認した
  1.21.8のresource 64、1.21.10のresource 69 / data 88を使う。Forge・NeoForgeの1.21.10はdata 88で宣言する。
- ForgeのEventBus 7は1.21.6から。`ForgeMod`・`ForgeClientSetup`を使い、`AddFramePassEvent`で経路の描画を登録する。
  1.21.8のキー・HUD登録はmod bus、1.21.10は各イベントの`BUS`へ登録する。
  `PassDefinition#executes`は1.21.8では引数なし、1.21.10では`LevelRenderState`を取る。
  `ForgeLevelRendererMixin`は公式の描画パスAPIが無い1.21.4・1.21.5にだけ残す。
- NeoForgeの`RenderLevelStageEvent`が段階別のサブクラスになるのは1.21.6から。
  1.21.10ではイベントからcameraを取れないため、Minecraftのmain cameraを使う。
- 1.21.9からキー入力は`KeyEvent`、キーバインドのカテゴリは`KeyMapping.Category`。
  Xaeroの世界地図のキー注入とruntime probeも同じ境界で分岐する。
- Fabricの1.21.10では`rendering.v1.world.WorldRenderEvents.END_MAIN`と`HudElementRegistry`を使う。
  1.21.8は従来の`WorldRenderEvents.AFTER_TRANSLUCENT`を使う。
- 1.21.8・1.21.10の線はmain targetへ描く。Forgeの追加パスはFabulous!の合成後なので、
  バニラの`RenderType.lines()`が使うitem_entity targetへ描くと画面に合成されない。
- Xaeroの下限は実際にビルドで使うWorld Map 1.46.0 / Minimap 26.5.0に合わせる。
  中間のMinecraft版へjarの対応範囲を広げる場合は、別途起動とフックの実行を確認する。

## 1.21.11

1.21.1との差が大きいのは描画・Forge/NeoForgeのイベント・入力まわり。

- **名前だけ変わったクラスはStonecutterの置換で吸収する**（`stonecutter.gradle.kts`の`replacements`）。
  `ResourceLocation`→`Identifier`、`Boat`のパッケージ移動。置換は双方向なので、置換後の名前をソースに直接書かない
- **深度テストはRenderPipelineが持つ**。地形越しに見せるレイヤーは、標準のパイプラインから深度テストだけを外した
  自前のパイプラインで作る（`client/NavRenderTypes`）。線は頂点ごとに線幅を持つ（`setLineWidth`）
- **線はmainターゲットへ描く**（バニラの`RenderTypes.lines()`はitem_entity）。Forgeの追加パスはFabulous!の合成より後に
  走るので、item_entityへ描いても画面へ合成されない
- **ワールドへの描画の入口**: Fabricは`WorldRenderEvents.END_MAIN`（`rendering.v1.world`パッケージ）。Forge 61には
  `RenderLevelStageEvent`が無く、`AddFramePassEvent`で描画パスを足す。NeoForgeは`RenderLevelStageEvent`が段階ごとの
  イベントに分かれ、`AfterTranslucentBlocks`を使う。どれもmodelViewに視点が積まれているので、`PathRenderer`へは単位行列を渡す
- **Forge 61はEventBus 7**。イベントがそれぞれ`BUS`を持ち、注釈での購読とは形が違うので、入口を
  `platform/forge/ForgeMod`・`ForgeClientSetup`に分けている（`ForgeEntry`・`ForgeEvents`は1.21.1以前用）
- **高さは`getMinY`/`getMaxY`で、上端を含む**。`util/GameCompat`は旧来どおり「上端を含まない」値で返す。
  ネザーの判定（旧`ultraWarm`）は環境属性`WATER_EVAPORATES`。バイオームで変わる属性なので位置を渡して読む
  （`getDimensionValue`はNeoForgeの開発実行で例外になる）
- **キー設定のカテゴリ**は`KeyMapping.Category`。表示名は`key.category.xaeronav.main`。NeoForgeではバニラの
  `Category.register`が非推奨で、`RegisterKeyMappingsEvent#registerCategory`で登録する
- **Xaeroの描画先**が`xaero.lib.client.graphics.XaeroBufferProvider`に変わった。mixinの注入先はその`endBatch()`
- **`pack.mcmeta`**は`min_format`/`max_format`で書く。Forge・NeoForgeは同じファイルをデータパックとしても検証するので、
  Forge自身と同じくデータの形式（94）で宣言する（`packFormatFields`）
- 1.21.11のFabricにアクセスワイドナーは要らない（開放していたクラスごと無くなった）

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
