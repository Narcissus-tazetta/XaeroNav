# XaeroNav

English | [日本語](README.ja.md)

**A client-side mod that computes an actually walkable route to a destination and draws it in three
places — the world, Xaero's World Map, and Xaero's Minimap — with turn-by-turn guidance on screen.**

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.228+ |
| Side | **Client-only** (no server install needed) |
| License | MIT |

---

## What it does

- **Routes over real terrain**, not straight-line distance. The A* search combines walking,
  climbing, descending, swimming, riding a boat, ladders/vines, jumping 1–3 block gaps, digging,
  and bridging over gaps with placed blocks.
- **Colors each segment by its type.** Blocks to be dug are highlighted through walls, and the
  very next segment to dig gets an outline.
- **Warns about danger with color.** Digging next to lava, digging that lets water flow in, a void
  below, a swim that runs out of breath, a fall that deals damage (when allowed).
- **Solves long distance in three tiers.** Beyond loaded chunks, a coarse route is drawn from
  Xaero's map data and the detailed search is stitched onto it segment by segment. This also
  handles dimensions where multiple floors stack at the same XZ, such as the Nether.
- **Surfaces before beelining underground.** When heading to a surface destination while
  underground, it first routes to the nearest exit (cave/cliff) instead of digging straight up
  toward the target (sky-having dimensions only).
- **Computes a real 3D path while gliding with an elytra**, avoiding terrain, with its own
  deviation threshold and recalculation interval separate from walking.
- **Recalculates conservatively.** Straying a few blocks from the line doesn't trigger a redraw,
  so the guidance doesn't flicker while you walk.
- **Works without Xaero too.** Only map drawing and the right-click menu are disabled; in-world
  rendering and the HUD still work.

## Setting a destination

| Method | Action |
|---|---|
| World map | Right-click empty space on the map → "Route here" |
| Waypoint | Right-click a waypoint → "Route here" |
| Keybind | "Route to block looked at" (unbound by default) |
| Command | `/xaeronav goto <x> <y> <z>` |

Starting to glide with an elytra automatically switches the guidance: it computes and shows a
terrain-avoiding aerial path (a light-blue line), and lands back onto walking navigation to the
same destination the moment you touch down.

Clear the route with `/xaeronav clear` or its keybind.

### Commands

| Command | What it does |
|---|---|
| `/xaeronav goto <x> <y> <z>` | Set the destination |
| `/xaeronav clear` | Clear the route |
| `/xaeronav version` | Print the running build (include this in bug reports) |

`/xaeronav debug ...` holds measurement commands that print numbers to chat without navigating
anywhere: `mapdata [radiusChunks]` (how much of Xaero's map data is available around you), `route`
and `corridor` (the coarse waypoint chain and its per-leg refinement), `probe` (what the detailed
search reached, and why it stopped), and `flight` (the aerial route). They exist to explain a route
that came out wrong — attach their output to a bug report.

### Keybinds

All **unbound by default** (`Options → Controls → XaeroNav`).

| Action | Purpose |
|---|---|
| Route to block looked at | Main way to set a destination without Xaero installed |
| Clear route | |
| Toggle HUD | Show/hide the on-screen guidance (persisted to the config file) |
| Open config screen | Edit `config/xaeronav-client.toml` via GUI |

## Route colors

| Color | Meaning |
|---|---|
| 🟢 Green | Walking |
| 🟡 Yellow | Climbing up |
| 🔵 Blue | Climbing/stepping down |
| 🔷 Dark blue | Swimming |
| 🩵 Light cyan | Riding a boat |
| 🟣 Purple | Ladder / vine |
| 🌸 Pink | Jumping a gap |
| 🟠 Orange | **Digging** (target block highlighted through walls) |
| 🩵 Cyan | **Bridging** with placed blocks |
| 🔴 Red | ⚠ Adjacent to lava |
| 🟪 Magenta | ⚠ Void below |
| 🔵 Light blue | ⚠ Digging lets water flow in |
| 🌹 Reddish pink | ⚠ Swim segment with no breath left |
| 🟧 Orange-red | ⚠ Fall that deals damage |
| 🟢 Teal | Fall softened by placing water at the last moment (MLG) |
| 🟠 Pale orange | ⚠ Sneaking across a magma block |
| ⚪ Off-white | Dotted line for a stretch with no known route (heads toward the unexplored destination) |
| 🟡 Amber | Coarse waypoint chain for a long-distance route |
| 🔷 Sky blue | Aerial path while gliding with an elytra |

Warning colors take priority over movement-type colors — danger needs to read first.

## Configuration

`config/xaeronav-client.toml`. **Also editable via the "Config" button in the Mods list.**

### `[pathfinding]`

| Key | Default | Description |
|---|---|---|
| `diggingEnabled` | `true` | Allow digging in routes |
| `bridgingEnabled` | `true` | Allow placing blocks to bridge gaps or climb cliffs |
| `lavaBridgingEnabled` | `true` | Allow bridging over lava (also requires `bridgingEnabled`; last resort when no route avoiding lava exists) |
| `jumpGapEnabled` | `true` | Allow jumping gaps up to 3 blocks wide |
| `fallDamageToleranceEnabled` | `false` | Allow descents that deal fall damage (up to 1/3 of health at search time; with a water bucket, MLG descents are also considered) |
| `deepLookAheadEnabled` | `true` | Keep extending the route ahead as far as loaded chunks allow while walking |
| `costToGoGuideEnabled` | `true` | Use the coarse route's cost estimate as an additional heuristic for detailed search (helps in 3D mazes like the Nether) |
| `detailHorizonBlocks` | `96` | Max horizontal distance the detailed search targets in one shot; farther destinations get intermediate waypoints |
| `maxBridgeRunBlocks` | `30` | How many consecutive blocks a bridge over open air can run before it's abandoned for a detour (`0` = unlimited) |
| `maxLavaBridgeRunBlocks` | `30` | Same, but specifically for bridges over lava (`0` = unlimited) |
| `maxSubmergedTicks` | `250` | How many ticks a route may keep your head underwater (`0` = unlimited) |
| `searchHorizontalMargin` | `64` | Horizontal search margin (blocks) |
| `searchVerticalMargin` | `32` | Vertical search margin (blocks) |
| `deviationThresholdBlocks` | `4.0` | Recalculate once you're this far from the line; higher keeps the line steadier |
| `arrivalRadiusBlocks` | `3.0` | Distance counted as "arrived" |
| `groundLevelY` | `60` | Y level and above, with open sky, counted as "surface" (basis for surface-first routing; inactive in dimensions without sky) |
| `recalcIntervalTicks` | `40` | Interval for checking block changes along the route |
| `maxExpandedNodes` | `100000` | Cap on nodes expanded per search; higher reaches farther accurately but costs more CPU/memory |
| `heuristicWeight` | `1.5` | How much the search favors getting close to the goal; `1.0` guarantees the shortest path but can fail to reach destinations where real cost (digging, swimming) outruns the estimate |
| `flightRoutingEnabled` | `true` | Compute an aerial path while gliding/flying (`false` reverts to a straight line to the destination) |
| `flightCellBlocks` | `6` | Side length (blocks) of the grid used to solve the aerial path; smaller fits through tighter gaps but reaches less far |
| `flightDeviationThresholdBlocks` | `24.0` | Recalculate the aerial path once you're this far from it |
| `flightRecalcIntervalTicks` | `20` | Recalculation interval (ticks) while gliding |
| `flightClearanceDetourBlocks` | `12` | How many blocks of detour a tight passage is worth avoiding (`0` disables this) |
| `flightMaxExpandedNodes` | `150000` | Cap on cells expanded per aerial search |
| `flightExtendMaxExpandedNodes` | `60000` | Cap on cells expanded when extending the path further from its end |
| `flightHeuristicWeight` | `2.5` | Heuristic weight for the aerial search |
| `additionalDiggableBlocks` | `[]` | Extra block IDs allowed to dig (e.g. modded terrain blocks; example: `"minecraft:cobblestone"`) |
| `additionalForbiddenBlocks` | `[]` | Extra block IDs forbidden to dig; takes priority over the list above (example: `"minecraft:diamond_ore"`) |

By default, digging only allows naturally generated terrain (stone, dirt, sand, ores, leaves,
netherrack, etc.). Processed blocks (cobblestone, stone bricks, planks) and blocks with inventory
(chests, furnaces) are never dug, and unrecognized blocks default to not-diggable.

### `[display]`

| Key | Default | Description |
|---|---|---|
| `hudEnabled` | `true` | On-screen guidance at the top of the screen |
| `straightLineEnabled` | `true` | Show a dotted line to the destination for stretches with no known route |

## Known limitations

- Search only covers loaded chunks and stops at the expanded-node cap. Far destinations get a
  route that ends partway, continuing as a dotted line (it fills in as you get closer); the HUD
  also shows "unexplored beyond this point."
- Search range is capped by Minecraft's **render distance** (render distance 8 = 128 blocks) —
  chunks the server hasn't sent can't be read, and there's no vanilla packet to request them. If
  long-distance guidance keeps cutting off, raise your render distance.
- Long-distance routing that relies on Xaero's map data isn't available without Xaero installed,
  or in areas you haven't visited yet (it falls back to computing from loaded chunks only).
- The HUD is hidden for aerial (elytra) routes — it only shows when a walking destination is set.
- Routes don't cross dimensions. Changing dimension clears the current destination.
- Map integration hooks into Xaero's internals. If a newer Xaero changes them, only that part
  switches off — XaeroNav says which part in chat once per session, and in-world rendering and the
  HUD keep working.
- Surface-first routing (surfacing before beelining underground) doesn't work in dimensions
  without sky (Nether, the End).

## Building

```bash
./gradlew build
```

This also runs `spotlessCheck` (unused imports, trailing whitespace, final newline) and the test
suite. Artifacts land in `build/libs/`.

Running a dev client:

```bash
./gradlew runClient                      # with Xaero
./gradlew runClient -Pwith_xaero=false   # without Xaero (to check fallback behavior)
```

Xaero is a compile-time-only dependency (`compileOnly`) and isn't bundled with the release.

### Layout

```
pathfinding/
  astar/    A* core, heap, heuristics, safety checks
  coarse/   Coarse terrain from Xaero's map / live sampling, and long-distance waypoint chains
  flight/   3D aerial pathfinding while gliding (coarse route, grid A*, smoothing)
  world/    Block reads (CellSource / ChunkView / CellData) and search bounds
  cost/     Movement and digging cost baselines
  async/    Worker-thread execution and cancellation
client/     Route state, rendering, HUD, guidance, commands, keybinds
mixin/xaero/  Hooks into Xaero's World Map / Minimap (required=false)
config/     TOML configuration
```

The search core never looks at the Minecraft world directly — it reads blocks through
[`CellSource`](src/main/java/net/prason/xaeronav/pathfinding/world/CellSource.java), a 4-method
window. The production implementation is `ChunkView`; tests pass `FakeCells`, which lets terrain
be written as text.

## License

MIT ([LICENSE](LICENSE)).

Movement cost baselines are informed by measurements used in
[Baritone](https://github.com/cabaletta/baritone) (LGPL), but the code itself is an independent
implementation.
