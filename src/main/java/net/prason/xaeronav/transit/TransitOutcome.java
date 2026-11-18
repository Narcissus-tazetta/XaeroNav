package net.prason.xaeronav.transit;

import org.jspecify.annotations.Nullable;

/**
 * 乗り換え案内の問い合わせの結果。{@code itinerary}があれば電車に乗る答え。{@code failure}があれば返事を読めなかった
 * （原因の英語の説明）。どちらも無ければ、返事は来たが電車に乗る区間が無い。
 */
public record TransitOutcome(@Nullable Itinerary itinerary, @Nullable String failure) {
}
