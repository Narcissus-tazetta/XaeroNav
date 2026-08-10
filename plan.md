# plan.md — 経路探索MOD 現状ステータスと次のアクション

設計の詳細は `pathfinding-mod-design.md` を参照。本ファイルは「どこまで確定していて、どこがまだ穴なのか」を正直に整理したもの。

---

## ◎ 検証済み・そのまま実装に使える部分

- **掘削コスト計算式**（Baritoneの実コードから確認）
  - `硬度 × 30 / ツール速度`（適正ツール）／`× 100`（不適正）＋固定2tick
  - 主要ブロックの早見表あり（design doc §3参照）
- **Xaero連携のフック手法**（XaeroPlusの実コードから確認）
  - 世界地図：`GuiMap#render` を `@WrapOperation` で包む
  - ミニマップ：`MinimapFBORenderer#renderChunksToFBO` を `@WrapOperation` で包む（**通常モードと洞窟モードで別ルート、両方必須**）
  - 座標変換パターン（カメラ位置を打ち消す平行移動）
- **エリトラ物理定数**（公式wikiから確認）
  - グライド比 約10:1、最低速度 約7.2 m/s

これらは実物のソース・公式情報に基づいているので、設計の土台として信頼して良い。

---

## ⚠️ 最大の未解決事項：Phase 0 検証（自分の手で行う必要あり）

参照したXaeroPlusは **MC 1.20.1向け** のコード。`xaero.*` パッケージの構造は同じバージョン系列なので流用できる可能性が高いが、1.21.1で完全に同一である保証はない。

`chocolateminecraft.com` のMavenはClaude側のネットワークアクセス範囲外にあり、**Claudeでは実物を取得・確認できない**。ここは実装に入る前に必ず自分の環境で潰す必要がある。

### やること
1. 1.21.1向けXaero devアーティファクトを取得
   ```
   repositories { maven { url "https://chocolateminecraft.com/maven" } }
   dependencies {
       implementation "xaero.lib:xaerolib-forge-1.21.1:+"
       implementation "xaero.map:xaeroworldmap-forge-1.21.1:<version>"
       implementation "xaero.minimap:xaerominimap-forge-1.21.1:<version>"
   }
   ```
2. Vineflower等でデコンパイルし、以下が現行版にも存在するか照合
   - `xaero.map.gui.GuiMap#render`
   - `MinimapFBORenderer#renderChunksToFBO`（クラス名・パッケージが変わっている可能性あり）
   - `xaero.common.mods.SupportXaeroWorldmap#drawMinimap(...)`
3. **ダミー描画（単色四角）だけをInject**して、想定通りの座標・タイミングで呼ばれるか確認
   - 通常モードのミニマップ
   - 洞窟モードのミニマップ（別経路なので個別に確認）
   - 世界地図（フルスクリーン）
4. ズレていたら、design doc §2-1 のクラスパスを実際のものに書き換える

### ここが通らなかった場合の逃げ道
design doc §2-5 で書いた通り、ワールド内描画（`RenderLevelStageEvent`）はXaero非依存で作れる。Xaero連携が思ったより崩れていた場合でも、ワールド内に経路を描く機能だけは死なない構成にしてある。

---

## △ 新たに気づいた設計上の懸念：ヒューリスティックの許容性

A*のヒューリスティックは「残り距離 × スプリント1マスのコスト(3.56tick)」としていたが、これが本当に**過小評価**になっているか未検証。

**懸念点**：Fall（落下）移動は加速するため、大きく落ちるケースではブロックあたりの実効コストがスプリントより安くなる可能性がある。もしそうなら、ヒューリスティックが実コストを超えてしまい（＝過大評価）、A*の最適性が崩れる。

### やること
- 実装時に、Baritoneの `FALL_N_BLOCKS_COST` 相当のtickテーブルを実際に計算し、最小コスト/マスがどこで発生するか確認する
- スプリントより安いケースがあれば、ヒューリスティックの基準値をその最小値に置き換える
- あるいは「Fallは別枠として、水平方向のみでヒューリスティックを計算する」という単純化で回避する手もある

---

## 次のアクション（優先順）

1. **Phase 0を自分の手で実施**（上記⚠️）— これが通らないと以降の設計の前提が崩れる
2. ヒューリスティックの許容性をFallコストで数値検証（上記△）
3. design doc の Phase 1（コスト関数・A*本体・非同期実行）に着手
4. Phase 2（描画：ワールド内 → Xaeroアダプタ層）
5. Phase 3（再計算トリガー・エッジケース・エリトラ・設定画面）

---

## 未確定・要判断（design doc §8と同じ、再掲）

- [ ] Baritoneのコードを直接流用するか、アイデア参考に留めるか（LGPLの扱い）
- [ ] 経路の視覚表現（色・太さ・高低差の表現）
- [ ] 掘削経路を別色で表示するか
- [ ] 対応MCバージョンの拡大戦略
