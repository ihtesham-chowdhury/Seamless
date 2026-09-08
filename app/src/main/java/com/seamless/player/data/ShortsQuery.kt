package com.seamless.player.data

/**
 * The single definition of what ends up in the shorts feed.
 *
 * Both the setup screen's "N videos ready" count and the feed itself go through here, so
 * the number you are shown and the number you get cannot drift apart.
 */
object ShortsQuery {

    /**
     * What "Recent" means, in seconds.
     *
     * A month is long enough to hold a weekend's filming and short enough that the view is
     * still narrowing something.
     */
    const val RECENT_WINDOW_SECONDS = 30L * 24 * 60 * 60

    /**
     * [all] should already have hidden folders excluded. [folderOverride] lets the library
     * play a single folder as a feed without disturbing the saved settings. [filter] is the
     * quick view selected on the tab, which the feed has to honour as well — see [narrow].
     */
    fun resolve(
        all: List<Video>,
        prefs: Prefs,
        folderOverride: Set<String>? = null,
        filter: ShortsFilter = ShortsFilter.ALL,
    ): List<Video> = narrow(sources(all, prefs, folderOverride), filter, prefs)

    /**
     * Which clips a quick view contains.
     *
     * Membership only — not order. The tab sorts what comes back; the feed shuffles it. This
     * is deliberately the one place the question is answered, because the answer has to be
     * the same in both: the wall showing eleven favourites and the feed then playing nine
     * hundred unrelated clips was the whole complaint.
     *
     * ALL and LONGEST hold everything. They differ from each other in order alone, which is
     * the tab's business and not the feed's.
     */
    fun narrow(videos: List<Video>, filter: ShortsFilter, prefs: Prefs): List<Video> =
        when (filter) {
            ShortsFilter.FAVOURITES -> videos.filter { prefs.isFavourite(it.id) }
            ShortsFilter.RECENT -> {
                val since = System.currentTimeMillis() / 1000 - RECENT_WINDOW_SECONDS
                videos.filter { it.dateModified >= since }
            }

            ShortsFilter.ALL, ShortsFilter.LONGEST -> videos
        }

    private fun sources(
        all: List<Video>,
        prefs: Prefs,
        folderOverride: Set<String>?,
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
