package com.reelbreaker.core

/**
 * A reel feed target. We detect reels by (package, view-id) pairs so that
 * normal browsing in the same app (DMs, profiles, stories) is NOT counted
 * or blocked — only infinite vertical/swipe feeds are intercepted.
 */
data class ReelTarget(
    val pkg: String,
    val label: String,
    /** resource-id substrings of the swipeable feed container */
    val feedViewIds: List<String>,
    /** true = vertical swipe feed (Reels/Shorts), false = horizontal card carousel (FB Reels tab) */
    val vertical: Boolean,
)

object KnownTargets {

    val ALL: List<ReelTarget> = listOf(
        ReelTarget(
            pkg = "com.instagram.android",
            label = "Instagram Reels",
            feedViewIds = listOf("boosted_frame_layout", "recycler_view"),
            vertical = true,
        ),
        ReelTarget(
            pkg = "com.google.android.youtube",
            label = "YouTube Shorts",
            feedViewIds = listOf("player_view", "youtube_shorts"),
            vertical = true,
        ),
        ReelTarget(
            pkg = "com.zhiliaoapp.musically",
            label = "TikTok",
            feedViewIds = listOf("aweme_feed", "viewpager"),
            vertical = true,
        ),
        ReelTarget(
            pkg = "com.ss.android.ugc.trill",
            label = "TikTok",
            feedViewIds = listOf("aweme_feed", "viewpager"),
            vertical = true,
        ),
        ReelTarget(
            pkg = "com.facebook.katana",
            label = "Facebook Reels",
            feedViewIds = listOf("reels_viewer", "carousel"),
            vertical = false,
        ),
        ReelTarget(
            pkg = "com.snapchat.android",
            label = "Snapchat Spotlight",
            feedViewIds = listOf("page_view", "recycler"),
            vertical = true,
        ),
    )

    fun forPackage(pkg: String): ReelTarget? = ALL.firstOrNull { it.pkg == pkg }
}
