# ADR-002: Asynchronous state ownership

- Status: Accepted
- Scope: route computation, cancellation, and rendering snapshots

## Decision

Minecraftのworldへ触れて探索入力を準備する処理と、重い経路探索を別のスレッド境界に置きます。

- クライアントスレッドは、プレイヤーとworldの参照、読み込み済みチャンクからの`ChunkView`構築、
  Xaero地図データの取得、状態遷移を担当する。
- `PathfindingExecutor`のワーカーは、渡されたビューを占有してセル読み取り、A*、危険注釈を行う。
- 新しい要求、clear、logout、次元変更ではgenerationを進める。完了したfutureは、自分が取得した
  generationと現在値が一致するときだけ結果を公開できる。
- HUD、ワールド描画、地図描画は、個別の可変フィールドを組み合わせず、1フレームにつき1つの
  immutable `NavigationView`を読む。

## Invariants

- `CellSource`と`ChunkView`は単一ワーカーが占有する。キャッシュを持つ同じインスタンスを並行する
  探索へ渡してはいけない。
- 通常予算とdeep fallbackを並行実行するときは、それぞれ独立したビューを渡す。
- futureの完了callbackからMinecraftのUI、player、worldを直接変更しない。必要な結果はスレッド安全な
  受け渡しを通し、クライアントtickで適用する。
- キャンセルは計算量を減らすために行うが、正しさはgeneration照合で守る。割り込みが遅れても古い結果を
  復活させてはいけない。
- `NavigationView`へ含める状態を変更したら、全ての書き込み経路でsnapshotを再発行する。
- logout後はgoal、route、世代、Xaeroの一時waypoint、worldを保持するビューを残さない。

## Why

探索は数百ms以上かかることがあり、クライアントスレッドで実行すると描画と入力を止めます。一方、
Minecraftのチャンク管理やXaeroの地図APIを任意のワーカーから操作することもできません。また、単なる
futureのキャンセルだけでは、完了直前の古い処理が新しい状態を上書きする競合を防げません。

明示的な所有権、generation、immutable snapshotの3つを組み合わせ、重い処理を逃がしながら表示状態の
一貫性を保ちます。

## Verification

- `PathfindingExecutor*Test`: 通常探索、deep fallback、緩和、粗いガイド
- `DiagnosticJobRunnerTest`: 診断探索の世代管理
- `StuckTrackerTest`: 状態機械から抽出した詰み判定
- `NavHud*Test`, `MapPathOverlayTest`, `PathGeometryTest`: 公開snapshotの利用側
- `CliffSpliceTest`, `SeamRepairSectionTest`, `SpliceJoinTest`: 非同期結果を使う経路差し替え

時系列を横断する`PathfindingState`全体の決定的な統合テストは、まだ未整備です。状態機械をさらに分割
するときは、fake scheduler/executorによる統合テストを先に追加します。

## Code map

- `client/PathfindingState.java`
- `client/FlightNavState.java`
- `pathfinding/async/PathfindingExecutor.java`
- `pathfinding/async/DiagnosticJobRunner.java`
- `pathfinding/world/CellSource.java`
- `pathfinding/world/ChunkView.java`
