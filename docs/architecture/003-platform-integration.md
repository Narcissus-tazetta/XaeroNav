# ADR-003: Loader, Xaero hook, and distribution contracts

- Status: Accepted
- Scope: supported loader nodes, optional Xaero integration, CI, and release artifacts

## Decision

1つの共有ソースからStonecutterでMinecraft版とローダーのノードを作ります。ノード一覧と依存バージョンを
別々の場所へ重複定義せず、`settings.gradle.kts`と`stonecutter.properties.toml`を正典にします。

Xaero's World MapとXaero's Minimapは任意依存です。Xaeroが無くても、ワールド内経路とHUDは動作します。
Xaero連携Mixinは`required=false`とし、外部modの変更で注入に失敗してもXaeroNav本体を起動不能にしません。
その代わり、注入失敗を黙らせず、ゲーム内のhealth表示とruntime CIのpositive assertionで検出します。

## Invariants

- Fabric jarには`fabric.mod.json`、NeoForge jarには`neoforge.mods.toml`、Forge jarには`mods.toml`だけを
  ローダーmetadataとして含める。
- Forgeは版にかかわらず、配布jarのmanifestに`MixinConfigs`を含める。`mods.toml`の`[[mixins]]`
  だけを根拠にしてはいけない。
- SRG名前空間で動くForge 1.20.1 jarにはrefmapを含める。公式mappingで動くForge 1.21.1へ同じ前提を
  持ち込まない。
- Forgeへ同梱するMixinExtrasを含む統合jarを配布し、slim jarを配布対象にしない。
- Xaeroの最低対応版は、各Mixinの実際の注入先を確認した版に合わせる。推測で下限を広げない。
- Releaseは`verifyDistribution`で、jar数、名前、version、metadata、manifest、refmapを公開前に検査する。
- CIは全ノードをmatrixでビルドし、client runtimeとForge dedicated-server smokeで実行時の契約を検査する。
- 外部GitHub Actionはcommit SHAへ固定し、build jobにはリポジトリ書き込み権限を与えない。

## Client-only contract

XaeroNavはクライアント専用であり、サーバーへインストールする必要はありません。Fabricはmetadata、
NeoForgeはentrypointのdist指定でクライアントに限定します。Forgeの`@Mod`には同等のdist引数がないため、
外側のentrypointが専用サーバーで読み込まれてもクライアントクラスを早期ロードしない構造を保ちます。

専用サーバーへ誤ってjarを置いた場合のForgeの起動互換性は、配布jarを使うCI smoke testで守ります。

## Verification

- Releaseの`./gradlew build verifyDistribution`: 全ノードのテストと集約した配布jar契約
- `.github/workflows/ci.yml`のbuild matrix: 全ノードのコンパイル
- 同workflowのclient runtime matrix: Minecraft起動とXaero hook適用
- Forge dedicated-server smoke matrix: クライアントクラスの早期ロード防止

## Code map

- `settings.gradle.kts`
- `stonecutter.properties.toml`
- `build.*.gradle.kts`
- `stonecutter.gradle.kts`
- `src/main/java/net/prason/xaeronav/platform/`
- `src/main/java/net/prason/xaeronav/mixin/xaero/`
- `src/main/resources/META-INF/`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

ローダーごとの具体的な追加手順と既知の版差は、[multiloader guide](../multiloader.md)を参照してください。
