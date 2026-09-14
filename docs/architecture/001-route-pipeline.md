# ADR-001: Three-stage route pipeline

- Status: Accepted
- Scope: walking routes and their long-distance guidance

## Decision

XaeroNav は長距離の徒歩経路を、解像度と責務の異なる3段階で扱います。

1. `CoarseRouter` は Xaero の地図データから、チャンク単位の大局的な中間目標列を作る。
2. `CorridorLegSolver` は粗い各区間を、地表データに基づくブロック解像度の廊下へ精緻化する。
3. `AStarPathfinder` は読み込み済みチャンクの実ブロックを使い、プレイヤーが実際に辿る移動列を作る。

層1と層2の出力は、実際に踏む経路ではなく層3へ向きを伝える中間目標です。中間目標の座標へ完全一致
することを要求してはいけません。粗いセルの代表点を強制すると、通れる場所がセル内にあっても不必要な
遠回りや失敗になります。

## Invariants

- 各層のコストはtickを単位とし、同じ移動を層ごとに矛盾する価格で評価しない。
- 層3のヒューリスティックへ渡すcost-to-goは実コストの下限でなければならない。下限性を失う変更は、
  A*の最適性と探索順を変えるため禁止する。
- 未知チャンクは通行不能にしない。未探索地を挟む目的地へ進めなくなるためである。一方、既知の安全な
  迂回路を常に捨てないよう、層1では既知の陸より高いコストにする。
- 層2は持ち物、体力、正確なブロック形状を知らない。掘削、ブロック設置、痛い落下など、その情報を
  必要とする最終判断は層3だけが行う。
- 層1の中間目標間隔は、層3が一度に解く詳細探索距離より短く保つ。
- ネザーでは状態を `(chunkX, chunkZ, floor)` として扱う。同じXZの独立した床を安い段差として
  連結してはいけない。

## Why

読み込み済みチャンクだけで遠距離を詳細探索すると、目的地まで地形が存在せず経路が途切れます。
反対に、地図由来の粗い線をそのまま歩行経路にすると、橋、掘削、当たり判定、持ち物、安全性を判断
できません。大局的な方向と実際の移動を分離することで、ストリーミング中も案内を継ぎ足せます。

## Verification

- `GuideAdmissibilityTest`: cost-to-goの下限性
- `CoarseRouterTest`, `CoarseWaypointFidelityTest`: 層1の経路と中間目標
- `CorridorWaypointsTest`, `SurfaceGridTest`: 層2の精緻化
- `AStarPathfinderTest`, `PathOptimalityTest`, `LongRouteOptimalityTest`: 層3の移動と最適性
- `NetherCaveLayerSlabReproTest`, `NetherThinMapGuideTest`: 複数床を持つ次元
- `ProgressiveDiscoveryTest`, `ProgressiveWalkTerrainTest`: 地形読み込み中の継ぎ足し

## Code map

- `pathfinding/coarse/CoarseRouter.java`
- `pathfinding/corridor/CorridorLegSolver.java`
- `pathfinding/astar/AStarPathfinder.java`
- `client/PathfindingState.java`
