# Minecraft 1.16.5 移植メモ

目標は Fabric と Forge の配布 jar を Java 8 で動かすこと。現時点で対応済みとは扱わない。
通常の `buildAll` と CI に試作ノードは含めない。

## 検証済みの依存

- Xaero 公式 Maven の 1.16.5 向け座標: XaeroLib 1.7.3、World Map 1.46.0、Minimap 26.5.0（Fabric / Forge とも公開済み）。
- Fabric API `0.42.0+1.16`、Mod Menu `1.16.23` は 1.16.5 向けに公開済み。
- Forge の最新 1.16.5 版は `36.2.42`。

## Fabric の試作

`./gradlew -Pexperimental_1165=true :1.16.5-fabric:compileJava` で試作ノードを有効化できる。
Fabric Loom 1.17.20 で Minecraft 1.16.5 のソース変換と Xaero 依存解決までは進む。
現在のコンパイルは失敗する。

最初の未解決 API は以下の通り。

- Minecraft 1.16.5 に存在しない `LevelHeightAccessor`、`TagKey`、`GuiGraphics`、
  `OptionInstance`、`TranslatableContents`、`BuiltInRegistries`。
- 1.16.5 より後に追加されたブロック型（`BigDripleafBlock`、`PowderSnowBlock`、
  `SculkShriekerBlock`）。
- Minecraft 1.16.5 には JOML の `Matrix4f` がなく、描画 API は旧版。
- Fabric API 0.42.0 は client command v2 を持たない。コマンドと描画イベントの入口を
  1.16.5 用に分岐する必要がある。

共通ロガーは 1.16.5 にもある Log4j 2 へ切り替えた。既存の 1.20.1 Fabric と
1.21.1 NeoForge の `compileJava` は通過済み。

## Java 8 と Forge

既存ソースは `record`、switch 式などを使う。Java 21 でコンパイルした後、
[JvmDowngrader](https://github.com/unimined/JvmDowngrader) で Java 8 バイトコードへ変換する方式を採用した。
Fabric・Forge とも `java8Jar` タスクが major version 52（Java 8）のクラスファイルを含む配布 jar を
生成できることを確認済み（`:1.16.5-fabric:build` / `:1.16.5-forge:build` とも成功）。
**ただし実際の Java 8 JVM上での起動確認はまだ行っていない**——後述のとおり dev 環境（Java 21）の
`runClient` すら現時点でXaero連携の途中で落ちる状態のため、Java 8 実機確認はその先の課題。

Forge は Architectury Loom（ForgeGradleではない）で `compileJava` まで成功する。
既存の 1.20.1 用 `legacyforge` ノードは使わない。

## 現状（2026-09-19時点）: `runClient` はMinecraft起動まで進むが、Xaero連携mixinで落ちる

`./gradlew -Pexperimental_1165=true :1.16.5-fabric:runClient` を実際に走らせて見つけた、
ビルドだけでは検出できない不具合を4件直した。

1. **重複fabric-loaderクラスでKnotが起動直後に落ちる**——1.16.5用ModMenu `1.16.23`が
   古い形式で`fabric-loader`への依存を直接持ち、Loomのremapが本来の`fabric-loader:0.19.5`とは
   別物として扱ってしまう。ModMenuの`modCompileOnly`/`modLocalRuntime`宣言で
   `exclude(group = "net.fabricmc", module = "fabric-loader")`して解決（`build.fabric.gradle.kts`）。
2. **「fabric-apiが無い」と判定されてmod解決に失敗**——Fabric API `0.42.0+1.16`（1.16.5時代の版）は
   本体モジュールのmod idが`fabric-api`ではなく`fabric`のまま（改名前）。共通の`fabric.mod.json`
   テンプレートは`"fabric-api": ">=..."`を決め打ちしていたため、1.16.5だけ食い違っていた。
   `fabricApiModIdFor(minecraftVersion)`（`XaeroNavBuild.kt`）で版ごとに正しいidへ切り替える。
3. **Mixinが「JAVA_8ではNESTINGを扱えない」と言って起動を拒否**——`mixin_compatibility_level`は
   実行時要件（`javaVersionFor`＝8）ではなく、mixinクラス自身がコンパイルされたバイトコードの
   言語機能で決める必要がある。1.16.5は常にJava 21でコンパイルしてから配布時にJava 8へ
   変換する方式なので、`runClient`（Java 21コンパイル直後、まだ変換前）のmixinクラスは
   NESTING等のJava 11以降の機能を含む。`mixinCompatibilityLevelFor`を`compileJavaVersionFor`
   基準に変更（`XaeroNavBuild.kt`）。**この値は配布jarにも同じものが焼き込まれる**——
   実Java 8機での`compatibilityLevel: JAVA_21`の扱いは未検証（下記「残っている疑問」参照）。
4. **Forgeの配布jarが`${mod_id}`等未展開のプレースホルダーのまま**——`build.forge-116.gradle.kts`は
   他ローダー（`build.forge.gradle.kts`等）にある`processResources`の`expand(...)`呼び出しを
   丸ごと持っていなかった。`compileJava`/`assemble`はリソース内容を検証しないため、
   `runClient`で実際にForgeがmods.tomlを読むまで発覚しなかった。他ローダーと同じ
   `processResources`設定を追加。

この4件を直した後、`:1.16.5-fabric:runClient`はMinecraft本体の起動（LWJGL初期化・全mod読み込み・
GL context作成）までは到達する。**その先、Xaero連携mixinの`GuiMapMixin`（世界地図への経路描画）の
適用中に致命的クラッシュで落ちる**。

原因は2つ重なっている。

- Xaero World Map `1.46.0`（1.16.5向け配布）の`GuiMap.render`は**LocalVariableTableを一切持たない**
  （`javap -l`で確認——デバッグ情報無しでコンパイルされている）。既存の`GuiMapMixin`は
  MixinExtrasの`@Local(name = "matrixStack")`のように**変数名でローカルを探す**書き方をしており、
  名前情報が無い1.16.5のXaeroビルドでは原理的に解決できない。
- さらに、この版の`render`メソッドで最初に呼ばれる`MultiBufferSource$BufferSource.endBatch()`
  （現行mixinが`ordinal = 0`で狙っている注入点）は、メソッドの**先頭近く**（`shouldReinit`チェックの
  直後）にあり、地形描画バッファの構築より前の「前フレームの取り残しをflushする」呼び出しに見える。
  1.20.1/1.21.1のXaeroが想定する「地形を描いてから1回目のflush」という構造とは対応点が違う
  可能性が高い（要デコンパイルでの裏取り。javapのバイトコードだけでは断定できない）。

`required: false`はmixin configの「対象クラスが見つからない」場合を救うだけで、
`@Local`の解決失敗（sugar validation）のような**より深い段階の例外は素通りしてクラッシュする**。
そのため今回は静かに無効化されず、ゲーム全体が落ちた。

`GuiMapKeyMixin`・`GuiMapRightClickMixin`・`MinimapFBORendererMixin`・`WaypointReaderMixin`の
残り4本についても、同じ理由（デバッグ情報なし・注入点の構造差）で同様に壊れている可能性が高いが、
**個別には未確認**（`GuiMapMixin`のクラッシュで`runClient`自体がその場で落ちるため、後続の
mixinが検証される前に終わっている）。

### 続き（同日）: Vineflowerでの裏取り・Fabricは前進、Forgeは別の壁

Vineflower（`org.vineflower:vineflower:1.10.1`、gradleキャッシュに既にあった）で1.16.5向けの
Xaero World Map / Minimapのjarを実際にデコンパイルし、`GuiMap#render`・
`MinimapFBORenderer#renderChunksToFBO`の実際の構造を確認した。

**`GuiMapMixin`は直った**（実行確認済み）。`endBatch()`のordinal 0は、地形描画より前に
「前フレームの取り残しをflushする」別の呼び出しで、`flooredCameraX`/`flooredCameraZ`もまだ
存在しない地点だった。1.17+はこの余分な呼び出しが無くordinal 0で正しい。1.16.5だけ
`//? if <1.17`でordinal 1（地形＋オーバーレイを実際にflushする2回目の呼び出し）に分岐した。
`@Local(name = ...)`は変数名がそのまま使えた——最初の「デバッグ情報が無い」という判断は誤りで、
javapが単に拾えていなかっただけだった（MixinExtrasの`@Local(print = true)`で実際の候補表を
出させて確認：`matrixStack`・`flooredCameraX`（ordinal 21）・`flooredCameraZ`（ordinal 22）が
名前つきで載っていた）。根本原因は**名前ではなくordinal（注入点そのもの）**だった。

**`MinimapFBORendererMixin`も同じ手口で直した**（デコンパイルで確認、`runClient`では
未検証——後述の別の壁でXaeroの初期化に到達できていない）。1.16.5の`renderChunksToFBO`は
ordinal 0が`this.mc.renderBuffers().bufferSource().endBatch()`（メインゲーム側の別バッファ）で、
狙うべき`renderTypeBuffers`自身のflushはordinal 1。ordinal 0のままだと例外にはならないが
**別バッファへ描いてしまい経路がミニマップに出ない**、という静かな不具合になっていたはず。
同じ`//? if <1.17`パターンで分岐した。

残り3本（`GuiMapKeyMixin`・`GuiMapRightClickMixin`・`WaypointReaderMixin`）は
`@Local`を使わず`@Inject`/`@ModifyReturnValue`のみなので、この種の問題は無い。

**Fabricは`runClient`でMixin適用が成功するところまで確認できた**（GuiMap変換が失敗しなくなった）。
ただしこのMac（Apple Silicon、Rosetta経由でx86_64 JVM）では、Minecraft本体が
`Window.setIcon`で`GLFW error 65548: Cocoa: Regular windows do not have icons on macOS`を
投げて`Minecraft.<init>`自体がハングする。1.21.1-fabricは同じマシンでこの問題が起きないため
（`Window`側のコード自体が新しいバージョンでは変わっている）、**1.16.5特有・このdev環境特有の
問題**と判断。CIはLinux（ubuntu-latest）で動くので影響しない見込みだが未確認。
このMacで実際にプレイして確認する手段は今回見つけていない。

**Forgeは別の、より深い壁に当たった**。

1. Forgeがバンドルするmixinフォーク（`dev.architectury:mixin-patched:0.8.4.12`）は
   `CompatibilityLevel`にJAVA_21が無く（列挙の最大がJAVA_18）、「JAVA_21 which is not recognised」
   で即死する。1.16.5だけ`min(compileJavaVersionFor, 18)`へ天井を付けた
   （`mixinCompatibilityLevelFor`、`XaeroNavBuild.kt`）。1.16.5のmixinクラスが要る機能は
   NESTING（Java 11以降）だけなので18で十分。他バージョンは無変更（Fabricの新しいMixinでは
   21のままで通っているので、そちらを18に下げると逆に壊すリスクがある）。
2. `build.forge-116.gradle.kts`はXaeroを`run/mods`へ置く仕組みが元々無かった（Fabricには
   あった）。素朴に「生の公開jarを`run/mods`へコピー」する方式（Fabricと同じやり方）を試したが、
   Forge（Architectury Loom）では**動かない**——`xaero.map.WorldMap`の`<clinit>`が
   `NoClassDefFoundError: net/minecraft/util/ResourceLocation`で落ちる。生jarはこの開発環境の
   マッピングへ変換されておらず、Fabricのように「mods フォルダに置いた生jarを実行時に
   自動remapする」仕組みがForge側には無いため。
3. `modLocalRuntime`（Loomの通常のmod remap経路）へ変えても**同じ症状**。さらに検証を進めると、
   **Xaeroを完全に外しても（`-Pwith_xaero=false`）同じ`NoClassDefFoundError`が起きる**うえ、
   `net/minecraft/world/World`のように**Xaeroと無関係な純粋なvanillaクラス**でも
   `Minecraft.runTick`自体が同じ例外で落ちた。つまりXaeroの統合とは無関係に、
   **`TransformingClassLoader`が実行中に一部のvanillaクラスを見失う**、Architectury Loom
   1.10.455 と Forge 36.2.42（`cpw.mods.modlauncher` 8.1.3、JDK 21実行）の組み合わせに
   起因する、より根本的な環境側の問題に見える。1.16.5世代のForge（1.17より前の古いFML/
   ModLauncher世代）はJDK 21のクラスローディング・モジュール周りとの相性問題が知られる領域で、
   これがそれに当たる可能性がある（未確認）。**runClient自体をJDK 17等の別JVMで走らせる
   必要があるかもしれない**——次にやるならここから。

### 続き（同日）：Fabricは実際に起動成功。Forgeはupstreamの既知バグで断念

**Fabricは直った。** `Minecraft.<init>`が投げていた`GLFW error 65548: Cocoa: Regular windows
do not have icons on macOS`は、1.16.5が既定で解決するLWJGL 3.3.2がmacOS（特にApple Silicon）で
ウィンドウアイコン設定に失敗する既知の問題（[LWJGL/lwjgl3#695](https://github.com/LWJGL/lwjgl3/issues/695)）。
1.16.5ノードだけLWJGLを3.3.3へ`resolutionStrategy`で強制して解決（`build.fabric.gradle.kts`）。
`runClient`は54個のmod（Xaero一式・XaeroNav含む）を全て読み込み、GL初期化も成功し、
**クラッシュせずメインメニューで2分以上安定して動き続けることを実際に確認した**。

**Forgeは断念——upstream（Architectury Loom）の既知の未解決バグに当たった。**
`net.minecraft.client.Options.<init>`が`NoClassDefFoundError: net/minecraft/client/settings/ToggleableKeyBinding`
で落ちる。バイトコードレベルで裏取りした結果、実際にビルドされた`Options.class`自体は
正しく`ToggleKeyMapping`（公式Mojang名）を参照しており、**ファイル自体は正しい**——
何らかの実行時の変換（Mixin/Architecturyのremapper等のTRANSFORMATIONSERVICE）が、古い
SRG名を動的に再導入している様子。

原因を[architectury-loomのissue #320](https://github.com/architectury/architectury-loom/issues/320)
（「Forge 1.16.5がSRGマップ済みメソッドでNSMEを起こす」）と、Loom 1.3の
[Legacy Forge実装のTODOリスト](https://github.com/architectury/architectury-loom/issues/148)
で確認した。後者に**メンテナ自身の手で未チェックのまま残っている項目**がある:

> - [ ] Fix all hardcoded instances of hardcoded SRG names in forge
> - [ ] Allow mapping class names from SRG to named in forge's own class remapper

これは**Forge自身の内部コードに、通常のバイトコード参照とは別にSRG名がハードコードされて
いる箇所がある**ことを、メンテナ自身が把握していて、まだ直していないということ。
`ToggleableKeyBinding`はまさにこの類（Forgeが独自に追加したキー設定パッチクラス）に見える。

試したが効かなかった対策:
- Forgeを36.2.42→36.2.39へ下げる（issue #320のコメントで「36.2.39は動く」と言及されていた版。
  ただしそちらは別のNSME症状の話で、`ToggleableKeyBinding`とは別物だった）
- Architectury Loomを1.10.455→最新の1.17.493へ上げる
- 両方を同時に試す

**いずれも同じ`NoClassDefFoundError`で落ちる。** これは設定やバージョン選びで回避できる
範囲を超えた、upstream側の未解決の欠陥と判断した。Forge 1.16.5を公式Mojangマッピングで
動かすには、Loom側の修正を待つか、ForgeGradle（MCP/SRGマッピング）へ切り替えて共有コード全体を
MCP名対応させる大改修が必要——後者は前回セッションで「886件のコンパイルエラー」として
一度見送った規模の作業になる。

**現状のまとめ**: `deps.forge = "36.2.39"`とASM/LWJGL forcing（`build.forge-116.gradle.kts`）、
Architectury Loom 1.17.493への更新は残した（Java 21対応として意味があり、他を壊していない）が、
**Forge 1.16.5のrunClientは現状では動かない**。Fabric 1.16.5は**動くようになった**。

### 残っている疑問（次にやるならここから）

- **Forgeは上記の理由で現状ブロック中。** 次に動かせる見込みがあるのは
  (a) architectury-loomの当該issueへ進捗が付くのを待つ、(b) ForgeGradle+MCPマッピングへ
  切り替えて共有コードのAPI呼び出しを`//? if <1.17`で全面対応させる大改修、のどちらか。
  どちらも今回のセッションの範囲外。
- Fabricは`runClient`でメインメニューまで確認したが、**実際にワールドへ入って地図を開き、
  Xaeroの経路描画（`GuiMapMixin`・`MinimapFBORendererMixin`）が動くところまでは
  未確認**——GUI操作の自動化（画面キャプチャの許可がこのセッションでは得られなかった）か、
  ユーザー自身の手動確認が必要。次にやるなら、実際にシングルプレイヤーワールドへ入り、
  世界地図・ミニマップに経路が描かれるか確認すること。
- `compatibilityLevel`をFabric側はJAVA_21、Forge側はJAVA_18で焼き込んでいる状態で、
  実Java 8 JVM上でMixinサブシステムが起動を許すか自体が未検証。
- 通常の`buildAll`・CIには1.16.5ノードをまだ含めていない（`-Pexperimental_1165=true`が無いと
  存在しない）。Forgeが動かない状態なので、CIへ混ぜる判断はまだできない
  （Fabricだけ先に混ぜる、という判断はユーザーに確認してから）。

### ついでに見つけた別件（1.16.5と直接は無関係）

正典ノード（`1.21.1-neoforge`）の`LanguageKeyTest`が、この作業中に赤くなっていることに気付いた。
原因は1.16.5用`XaeroNavConfigScreen`のレガシー実装（`//? if <1.17`分岐内、`OptionInstance`が無い
版のScreen）が使う`"gui.back"`/`"gui.next"`/`"gui.done"`——Minecraft本体が既に持つ翻訳キーを
そのまま再利用している箇所。`LanguageKeyTest`はstonecutterの条件分岐を理解せず生テキストを
正規表現で走査するため、**コメントアウトされた側（他バージョン用の分岐）の文字列リテラルも
拾ってしまう**。既存の`gui.xaero_`除外と同じパターンで、この3キーも除外に追加して直した
（`src/test/java/net/prason/xaeronav/LanguageKeyTest.java`）。1.16.5の作業を始める前から
このテストは（1.16.5関連のコードがコミットされていない状態でも）赤くなる状態だったので、
1.16.5対応そのものとは別に直しておく必要があった。
