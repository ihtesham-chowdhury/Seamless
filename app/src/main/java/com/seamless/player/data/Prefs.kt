package com.seamless.player.data

import android.content.Context
import androidx.core.content.edit

/** How the long-form player picks its screen orientation. */
enum class OrientationMode { FOLLOW_VIDEO, SENSOR, PORTRAIT, LANDSCAPE;
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: FOLLOW_VIDEO
    }
}

/** How video is scaled into the view. Maps onto Media3's AspectRatioFrameLayout modes. */
enum class ResizeMode {
    /** Whole frame visible, black bars where the shapes differ. */
    FIT,
    /** Fills the screen, cropping the overflow. */
    CROP,
    /** Fills the screen by distorting the picture. */
    STRETCH;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: FIT
    }
}

/**
 * How the shorts tab lays out its clips.
 *
 * Its own type rather than [LibraryView] with a third case: the library's grid is a wall of
 * 16:9 tiles with a name and a metadata line, and none of the three here has any of that.
 * Sharing the enum would mean every `when` in the library growing a branch for a layout it
 * cannot draw.
 */
enum class ShortsLayout {
    /** Two columns, each tile at its clip's own shape. The default. */
    MASONRY,

    /** Two columns, every tile the same. */
    GRID,

    /** Rows with names and metadata — the library's list, unchanged. */
    LIST;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: MASONRY
    }
}

/**
 * The shorts tab's quick views, the row of chips under the title.
 *
 * Each is a filter, an order, or both — whatever makes the label true. That mixture is
 * deliberate rather than sloppy: "Recent" has to narrow the list, because ordering by date is
 * already the default and a chip that changes nothing is worse than no chip; "Longest" has to
 * order it, because any threshold for what counts as long would be invented. The full sort
 * lives in the view-options sheet and applies to the two views that do not dictate one.
 */
enum class ShortsFilter {
    /** Everything, in whatever order the sheet says. */
    ALL,

    /** Added in the last month, newest first. */
    RECENT,

    /** Only what you have marked, in the sheet's order. */
    FAVOURITES,

    /** Everything, longest first. */
    LONGEST;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: ALL
    }
}

/** Library layout. */
enum class LibraryView { LIST, GRID;
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: LIST
    }
}

/** Sort keys. Not every key applies to both folders and videos. */
enum class SortKey {
    TITLE, DATE, SIZE, DURATION, COUNT,

    /**
     * No order at all.
     *
     * Shuffled from a seed rather than freshly each time, so the list stays put while you
     * scroll it. Re-picking Random draws a new seed, which is how you ask for another deal.
     */
    RANDOM;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DATE
    }
}

/** One screen's answer to "in what order, and which way round". */
data class SortSetting(val key: SortKey, val ascending: Boolean, val seed: Long)

/** How the user gets out of screen lock. */
enum class UnlockMethod {
    /** Drag a handle across the bottom of the screen. */
    SLIDER,
    /** Tap the four corners clockwise from the top left. */
    CORNERS;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: SLIDER
    }
}

/** Where the shorts feed draws its clips from. */
enum class ShortsSource {
    /** Only the folders the user ticked. Everything in them qualifies. */
    SELECTED_FOLDERS,
    /** Every video on the device, narrowed by the shape and length filters. */
    WHOLE_DEVICE;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: WHOLE_DEVICE
    }
}

/** Light / dark, or follow the device. Applies to the browsing UI, not the fullscreen players. */
enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * An accent colour for the browsing UI — bottom nav, buttons, switches — the same idea as
 * MX Player's colour picker. DEFAULT applies no overlay and keeps Material's own baseline
 * colour.
 */
enum class AccentColor {
    DEFAULT, RED, ORANGE, AMBER, GREEN, TEAL, CYAN, BLUE, INDIGO, PURPLE, PINK;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * All persisted settings and playback state.
 *
 * Resume positions live in their own file so a few hundred of them never slow down
 * reading ordinary settings.
 */
class Prefs(context: Context) {

    private val app = context.applicationContext
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resume = app.getSharedPreferences("resume", Context.MODE_PRIVATE)

    // ---- shorts feed ----

    /** Folders (MediaStore RELATIVE_PATHs) the shorts feed draws from. */
    var shortsFolders: Set<String>
        get() = settings.getStringSet(KEY_SHORTS_FOLDERS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_SHORTS_FOLDERS, value) }

    /** When a clip ends: move on (true) or loop it (false). */
    var shortsAutoAdvance: Boolean
        get() = settings.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = settings.edit { putBoolean(KEY_AUTO_ADVANCE, value) }

    var shortsResizeMode: ResizeMode
        get() = ResizeMode.from(settings.getString(KEY_SHORTS_RESIZE, null))
        set(value) = settings.edit { putString(KEY_SHORTS_RESIZE, value.name) }

    /**
     * Folders, or the whole device.
     *
     * Whole-device is the default now. It is the mode with nothing to set up: the tab has
     * clips to show the first time it is opened, rather than an empty checklist and a
     * disabled button. Picking folders is the deliberate choice, so it is the one that waits
     * to be made.
     */
    var shortsSource: ShortsSource
        get() = ShortsSource.from(settings.getString(KEY_SHORTS_SOURCE, null))
        set(value) = settings.edit { putString(KEY_SHORTS_SOURCE, value.name) }

    /**
     * How the shorts tab lays out its clips.
     *
     * Masonry by default. A wall of clips at their own shapes is what makes the tab read as
     * a collection rather than as a file listing, and the shapes are free: MediaStore
     * already gave us every clip's width and height, so a tile can be measured before its
     * thumbnail has loaded and nothing jumps when it does.
     */
    /** Which chip is selected under the title. Remembered, like everything else on the tab;
     *  the row is always on screen, so the state is never hidden from you. */
    var shortsFilter: ShortsFilter
        get() = ShortsFilter.from(settings.getString(KEY_SHORTS_FILTER, null))
        set(value) = settings.edit { putString(KEY_SHORTS_FILTER, value.name) }

    var shortsLayout: ShortsLayout
        get() = ShortsLayout.from(settings.getString(KEY_SHORTS_LAYOUT, null))
        set(value) = settings.edit { putString(KEY_SHORTS_LAYOUT, value.name) }

    /**
     * In whole-device mode, whether a landscape clip can qualify by being short. Portrait
     * clips always qualify regardless of length.
     */
    var includeShortLandscape: Boolean
        get() = settings.getBoolean(KEY_SHORT_LANDSCAPE, false)
        set(value) = settings.edit { putBoolean(KEY_SHORT_LANDSCAPE, value) }

    /** The cut-off for that rule, in seconds. */
    var maxLandscapeSeconds: Int
        get() = settings.getInt(KEY_MAX_LANDSCAPE_SEC, DEFAULT_MAX_LANDSCAPE_SEC)
        set(value) = settings.edit { putInt(KEY_MAX_LANDSCAPE_SEC, value) }

    // ---- appearance ----

    var themeMode: ThemeMode
        get() = ThemeMode.from(settings.getString(KEY_THEME_MODE, null))
        set(value) = settings.edit { putString(KEY_THEME_MODE, value.name) }

    var accentColor: AccentColor
        get() = AccentColor.from(settings.getString(KEY_ACCENT_COLOR, null))
        set(value) = settings.edit { putString(KEY_ACCENT_COLOR, value.name) }

    // ---- library ----

    /** Folders excluded from every scan, by RELATIVE_PATH. */
    var hiddenFolders: Set<String>
        get() = settings.getStringSet(KEY_HIDDEN_FOLDERS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_HIDDEN_FOLDERS, value) }

    fun hideFolders(paths: Collection<String>) {
        hiddenFolders = hiddenFolders + paths
    }

    fun unhideFolder(path: String) {
        hiddenFolders = hiddenFolders - path
    }

    /**
     * Folders that require authentication to open. Prefix-matched like every other folder
     * set, so locking a folder locks everything beneath it.
     */
    var lockedFolders: Set<String>
        get() = settings.getStringSet(KEY_LOCKED_FOLDERS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_LOCKED_FOLDERS, value) }

    fun isFolderLocked(path: String) = lockedFolders.any { path.startsWith(it) }

    /**
     * What the shorts feed must not touch. Locking a folder has to keep its contents out of
     * the feed as well, or a whole-device scan would put the very clips you locked away on
     * screen without asking for anything.
     */
    val foldersExcludedFromFeed: Set<String> get() = hiddenFolders + lockedFolders

    fun setFolderLocked(path: String, locked: Boolean) {
        lockedFolders = if (locked) lockedFolders + path else lockedFolders - path
    }

    /** Require authentication to open the app at all. */
    var appLockEnabled: Boolean
        get() = settings.getBoolean(KEY_APP_LOCK, false)
        set(value) = settings.edit { putBoolean(KEY_APP_LOCK, value) }

    var libraryView: LibraryView
        get() = LibraryView.from(settings.getString(KEY_LIBRARY_VIEW, null))
        set(value) = settings.edit { putString(KEY_LIBRARY_VIEW, value.name) }

    // ---- sorting, one screen at a time ----

    /**
     * Sorting is per screen, not global.
     *
     * A single global order is wrong the moment you have two kinds of folder: the library
     * reads best by name, a camera roll by date, a folder of clips in no order at all. So
     * every screen carries its own key, direction and shuffle seed, addressed by a scope
     * string — [SCOPE_LIBRARY] for the folder list, [SCOPE_SHORTS] for the shorts grid, and
     * a folder's own MediaStore RELATIVE_PATH for the videos inside it.
     *
     * Those three namespaces cannot collide: a RELATIVE_PATH always ends in a separator,
     * and neither constant contains one.
     *
     * A screen that has never been given an order falls back to the old global setting,
     * which is what anyone upgrading already chose, and to a sensible default beyond that.
     */
    fun sortFor(scope: String): SortSetting {
        val stored = settings.getString(KEY_SORT_KEY_PREFIX + scope, null)
        val key = if (stored != null) SortKey.from(stored) else defaultSortKey(scope)
        val ascending = settings.getBoolean(
            KEY_SORT_ASC_PREFIX + scope,
            settings.getBoolean(KEY_SORT_ASC, false),
        )
        return SortSetting(key, ascending, settings.getLong(KEY_SORT_SEED_PREFIX + scope, 0L))
    }

    /**
     * Writing Random draws a fresh seed, every time, including when Random was already the
     * key — that is what the sheet's "Shuffle again" button does, and a seed that never
     * changed would hand back the same order it just gave.
     */
    fun setSortKey(scope: String, key: SortKey) = settings.edit {
        putString(KEY_SORT_KEY_PREFIX + scope, key.name)
        if (key == SortKey.RANDOM) putLong(KEY_SORT_SEED_PREFIX + scope, System.nanoTime())
    }

    fun setSortAscending(scope: String, ascending: Boolean) =
        settings.edit { putBoolean(KEY_SORT_ASC_PREFIX + scope, ascending) }

    private fun defaultSortKey(scope: String): SortKey = when (scope) {
        SCOPE_LIBRARY -> SortKey.from(settings.getString(KEY_FOLDER_SORT, SortKey.COUNT.name))
        else -> SortKey.from(settings.getString(KEY_VIDEO_SORT, SortKey.DATE.name))
    }

    // ---- first-run tips ----

    /**
     * Which contextual hints have already been shown.
     *
     * One key per screen rather than a single "seen the tutorial" flag, so a screen added
     * later gets its own first showing instead of being silently covered by a flag set months
     * earlier.
     */
    private var seenTips: Set<String>
        get() = settings.getStringSet(KEY_SEEN_TIPS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_SEEN_TIPS, value) }

    fun hasSeenTip(key: String) = key in seenTips

    fun markTipSeen(key: String) {
        seenTips = seenTips + key
    }

    /** Puts every hint back, for anyone who wants the introduction again. */
    fun resetTips() {
        seenTips = emptySet()
    }

    // ---- pinning ----

    /**
     * Folders the user has pulled to the top of the library, and videos pulled to the top of
     * their folder.
     *
     * Pinning sits *outside* the sort order rather than being another sort key: the point is
     * to keep a handful of things reachable no matter how the rest is arranged, so changing
     * the sort must not move them. Within the pinned group the chosen sort still applies.
     *
     * Videos are keyed by MediaStore id, held as strings because that is what a
     * SharedPreferences string set stores.
     */
    var pinnedFolders: Set<String>
        get() = settings.getStringSet(KEY_PINNED_FOLDERS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_PINNED_FOLDERS, value) }

    fun isFolderPinned(path: String) = path in pinnedFolders

    fun setFoldersPinned(paths: Collection<String>, pinned: Boolean) {
        pinnedFolders = if (pinned) pinnedFolders + paths else pinnedFolders - paths.toSet()
    }

    var pinnedVideos: Set<String>
        get() = settings.getStringSet(KEY_PINNED_VIDEOS, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_PINNED_VIDEOS, value) }

    fun isVideoPinned(id: Long) = id.toString() in pinnedVideos

    fun setVideosPinned(ids: Collection<Long>, pinned: Boolean) {
        val keys = ids.mapTo(HashSet()) { it.toString() }
        pinnedVideos = if (pinned) pinnedVideos + keys else pinnedVideos - keys
    }

    // ---- favourites ----

    /**
     * Clips marked as favourites, by MediaStore id.
     *
     * Held as strings because that is what a SharedPreferences string set stores, the same
     * as pinning. Separate from pinning on purpose: a pin is about position, a favourite is
     * about the clip. One moves things to the top of a list, the other survives the list
     * being sorted a different way.
     */
    var favouriteVideos: Set<String>
        get() = settings.getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()
        set(value) = settings.edit { putStringSet(KEY_FAVOURITES, value) }

    fun isFavourite(id: Long) = id.toString() in favouriteVideos

    /** Flips the mark and reports what it now is, so a caller can animate the right way. */
    fun toggleFavourite(id: Long): Boolean {
        val key = id.toString()
        val now = key !in favouriteVideos
        favouriteVideos = if (now) favouriteVideos + key else favouriteVideos - key
        return now
    }

    // ---- folder covers ----

    /**
     * The video a folder borrows its thumbnail from, chosen by the user.
     *
     * Stored one key per folder rather than as a set of encoded pairs, so a path containing
     * any separator character we might have picked cannot corrupt the rest of the map.
     * 0 means "not chosen"; MediaStore ids are always positive.
     */
    fun folderCover(path: String): Long = settings.getLong(KEY_COVER_PREFIX + path, 0L)

    fun setFolderCover(path: String, videoId: Long) =
        settings.edit { putLong(KEY_COVER_PREFIX + path, videoId) }

    /** Back to the folder's first video, which is what an unchosen folder shows. */
    fun clearFolderCover(path: String) = settings.edit { remove(KEY_COVER_PREFIX + path) }

    // ---- last played ----

    /**
     * Enough to reopen the last video from the library: the folder gives the queue, the id
     * gives the position in it. Videos opened from another app are deliberately not
     * recorded — there is no library context to return to.
     */
    fun rememberLastPlayed(video: Video) = settings.edit {
        putLong(KEY_LAST_PLAYED_ID, video.id)
        putString(KEY_LAST_PLAYED_FOLDER, video.relativePath)
        putString(KEY_LAST_PLAYED_NAME, video.name)
    }

    val lastPlayedId: Long get() = settings.getLong(KEY_LAST_PLAYED_ID, 0L)
    val lastPlayedFolder: String? get() = settings.getString(KEY_LAST_PLAYED_FOLDER, null)
    val lastPlayedName: String? get() = settings.getString(KEY_LAST_PLAYED_NAME, null)

    // ---- playback ----

    /**
     * Only videos at least this long get a resume position. Short clips always start
     * from zero, which is what you want for a shorts feed.
     */
    var resumeThresholdMinutes: Int
        get() = settings.getInt(KEY_RESUME_MINUTES, DEFAULT_RESUME_MINUTES)
        set(value) = settings.edit { putInt(KEY_RESUME_MINUTES, value.coerceIn(1, 60)) }

    var orientationMode: OrientationMode
        get() = OrientationMode.from(settings.getString(KEY_ORIENTATION, null))
        set(value) = settings.edit { putString(KEY_ORIENTATION, value.name) }

    var playerResizeMode: ResizeMode
        get() = ResizeMode.from(settings.getString(KEY_PLAYER_RESIZE, null))
        set(value) = settings.edit { putString(KEY_PLAYER_RESIZE, value.name) }

    /**
     * Chosen playback speed, remembered across videos and sessions — someone watching a
     * long series at 1.5x wants the next episode at 1.5x too.
     */
    var playbackSpeed: Float
        get() = settings.getFloat(KEY_PLAYBACK_SPEED, 1f)
        set(value) = settings.edit { putFloat(KEY_PLAYBACK_SPEED, value.coerceIn(0.25f, 4f)) }

    /** Shuffle the folder queue in the ordinary player. The shorts feed is always shuffled. */
    var folderShuffle: Boolean
        get() = settings.getBoolean(KEY_FOLDER_SHUFFLE, false)
        set(value) = settings.edit { putBoolean(KEY_FOLDER_SHUFFLE, value) }

    var unlockMethod: UnlockMethod
        get() = UnlockMethod.from(settings.getString(KEY_UNLOCK, null))
        set(value) = settings.edit { putString(KEY_UNLOCK, value.name) }

    /** Dragging down anywhere on the video leaves the player. */
    var swipeDownToClose: Boolean
        get() = settings.getBoolean(KEY_SWIPE_CLOSE, true)
        set(value) = settings.edit { putBoolean(KEY_SWIPE_CLOSE, value) }

    /**
     * Keep the screen upright for videos opened from another app. Off by default, because
     * forcing portrait on a landscape video letterboxes it heavily.
     */
    var externalPortrait: Boolean
        get() = settings.getBoolean(KEY_EXTERNAL_PORTRAIT, false)
        set(value) = settings.edit { putBoolean(KEY_EXTERNAL_PORTRAIT, value) }

    /** Last brightness the user dialled in, so it survives leaving the player. 0f..1f, -1 = system. */
    var playerBrightness: Float
        get() = settings.getFloat(KEY_BRIGHTNESS, -1f)
        set(value) = settings.edit { putFloat(KEY_BRIGHTNESS, value) }

    // ---- resume positions ----

    private fun eligibleForResume(durationMs: Long) =
        durationMs >= resumeThresholdMinutes * 60_000L

    fun savePosition(video: Video, positionMs: Long) {
        if (!eligibleForResume(video.durationMs)) return
        // Near the end counts as finished; don't strand the user 3 seconds from the credits.
        val finished = positionMs >= video.durationMs - 5_000L
        resume.edit {
            if (finished || positionMs < 5_000L) remove(video.id.toString())
            else putLong(video.id.toString(), positionMs)
        }
    }

    fun loadPosition(video: Video): Long {
        if (!eligibleForResume(video.durationMs)) return 0L
        return resume.getLong(video.id.toString(), 0L)
    }

    fun clearAllPositions() = resume.edit { clear() }

    companion object {
        /** The folder list. */
        const val SCOPE_LIBRARY = "library"

        /** The shorts tab's grid of clips. */
        const val SCOPE_SHORTS = "shorts"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SHORTS_FOLDERS = "shorts_folders"
        private const val KEY_AUTO_ADVANCE = "shorts_auto_advance"
        private const val KEY_SHORTS_RESIZE = "shorts_resize_mode"
        private const val KEY_SHORTS_SOURCE = "shorts_source"
        private const val KEY_SHORTS_LAYOUT = "shorts_layout"
        private const val KEY_SHORTS_FILTER = "shorts_filter"
        private const val KEY_SHORT_LANDSCAPE = "shorts_include_short_landscape"
        private const val KEY_MAX_LANDSCAPE_SEC = "shorts_max_landscape_seconds"
        private const val KEY_LOCKED_FOLDERS = "locked_folders"
        private const val KEY_APP_LOCK = "app_lock"
        private const val DEFAULT_MAX_LANDSCAPE_SEC = 30
        private const val KEY_HIDDEN_FOLDERS = "hidden_folders"
        private const val KEY_LIBRARY_VIEW = "library_view"
        private const val KEY_FOLDER_SORT = "folder_sort"
        private const val KEY_VIDEO_SORT = "video_sort"
        private const val KEY_SORT_ASC = "sort_ascending"
        private const val KEY_SORT_KEY_PREFIX = "sort_key:"
        private const val KEY_SORT_ASC_PREFIX = "sort_asc:"
        private const val KEY_SORT_SEED_PREFIX = "sort_seed:"
        private const val KEY_RESUME_MINUTES = "resume_minutes"
        private const val KEY_ORIENTATION = "orientation_mode"
        private const val KEY_PLAYER_RESIZE = "player_resize_mode"
        private const val KEY_FOLDER_SHUFFLE = "folder_shuffle"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_UNLOCK = "unlock_method"
        private const val KEY_SWIPE_CLOSE = "swipe_down_to_close"
        private const val KEY_EXTERNAL_PORTRAIT = "external_portrait"
        private const val KEY_BRIGHTNESS = "player_brightness"
        private const val KEY_COVER_PREFIX = "folder_cover:"
        private const val KEY_SEEN_TIPS = "seen_tips"
        private const val KEY_PINNED_FOLDERS = "pinned_folders"
        private const val KEY_PINNED_VIDEOS = "pinned_videos"
        private const val KEY_FAVOURITES = "favourite_videos"
        private const val KEY_LAST_PLAYED_ID = "last_played_id"
        private const val KEY_LAST_PLAYED_FOLDER = "last_played_folder"
        private const val KEY_LAST_PLAYED_NAME = "last_played_name"
        private const val DEFAULT_RESUME_MINUTES = 10
    }
}
