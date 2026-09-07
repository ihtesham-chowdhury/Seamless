package com.seamless.player.data

/**
 * The single definition of what ends up in the shorts feed.
 *
 * Both the setup screen's "N videos ready" count and the feed itself go through here, so
 * the number you are shown and the number you get cannot drift apart.
 */
object ShortsQuery {

    /**
     * [all] should already have hidden folders excluded. [folderOverride] lets the library
     * play a single folder as a feed without disturbing the saved settings.
     */
    fun resolve(
        all: List<Video>,
        prefs: Prefs,
        folderOverride: Set<String>? = null,
    ): List<Video> {
        if (folderOverride != null) {
            return MediaLibrary.videosUnder(all, folderOverride)
        }
        return when (prefs.shortsSource) {
            // Whatever is in the chosen folders, exactly as before: if you picked the
            // folder, you meant everything in it.
            ShortsSource.SELECTED_FOLDERS ->
                MediaLibrary.videosUnder(all, prefs.shortsFolders)

            ShortsSource.WHOLE_DEVICE -> MediaLibrary.shortsCandidates(
                videos = all,
                includeShortLandscape = prefs.includeShortLandscape,
                maxLandscapeSeconds = prefs.maxLandscapeSeconds,
            )
        }.distinctBy { it.id }
    }
}
