# Changelog

All notable changes to XaeroNav are documented in this file.

## [0.2.0] - 2026-09-11

### Added

- Minecraft 1.20.1 support for Fabric and Forge, alongside the existing 1.21.1 builds.
- Forge 1.21.1 support (previously NeoForge and Fabric only).
- Much better routing in dimensions with a ceiling, such as the Nether — routes that
  previously stopped short or never appeared should now be found.

### Fixed

- Routes failing to appear when crossing wide lava seas in the Nether.
- Stale routes not updating while walking after being computed from a partially loaded map.
- Overlapping or detouring paths where route segments are stitched together.
- Routes taking unnecessary detours due to underestimated costs.
- Lava-only floors showing on the map where a walkable path actually existed.
- The waypoint marker (dashed line) not disappearing or jumping backward after being passed.
- No route being found at lava shorelines in some cases.
- Routes recalculating repeatedly for several seconds right after using `/xaeronav goto`.
- The HUD showing "arriving soon" / distance-remaining for intermediate waypoints instead
  of only the final destination.
- A crash-like MOD loading screen on Forge caused by a missing `pack.mcmeta`.
- Forge builds where MOD events never fired.

[0.2.0]: https://github.com/Narcissus-tazetta/XaeroNav/releases/tag/v0.2.0
