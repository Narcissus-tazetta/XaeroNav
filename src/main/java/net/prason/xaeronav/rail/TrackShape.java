package net.prason.xaeronav.rail;

/**
 * レールの向き。名前はバニラの{@code RailShape}と同じにしてあり、{@link #valueOf}で写す。
 *
 * <p>バニラの列挙をそのまま保存しないのは、保存データの意味をMinecraftの版から切り離すため。
 * 保存データには{@link #ordinal()}を書くので、並びを変えないこと。
 */
public enum TrackShape {
    NORTH_SOUTH,
    EAST_WEST,
    ASCENDING_EAST,
    ASCENDING_WEST,
    ASCENDING_NORTH,
    ASCENDING_SOUTH,
    SOUTH_EAST,
    SOUTH_WEST,
    NORTH_WEST,
    NORTH_EAST
}
