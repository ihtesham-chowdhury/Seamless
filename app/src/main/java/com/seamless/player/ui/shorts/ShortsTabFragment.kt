package com.seamless.player.ui.shorts

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.NavStyle
import com.seamless.player.data.Prefs
import com.seamless.player.data.ShortsFilter
import com.seamless.player.data.ShortsLayout
import com.seamless.player.data.ShortsQuery
import com.seamless.player.data.ShortsSource
import com.seamless.player.data.Video
import com.seamless.player.data.VideoFolder
import com.seamless.player.databinding.FragmentShortsBinding
import com.seamless.player.ui.MainActivity
import com.seamless.player.ui.common.SortLabels
import com.seamless.player.ui.common.ViewChoice
import com.seamless.player.ui.common.ViewOptionsSheet
import com.seamless.player.ui.library.VideoAdapter
import com.seamless.player.ui.nav.GlassView
import com.seamless.player.util.Background
import com.seamless.player.util.applyFloatingNavInset

/**
 * The Shorts tab.
 *
 * Two shapes, decided by a setting rather than by a question asked on arrival.
 *
 * **Whole device** — the default, and the one with nothing to set up. The tab is a wall of
 * every clip that qualifies; tapping one opens the feed on it. An earlier version opened the
 * feed automatically and left this screen with nothing on it but a sentence explaining that
 * it had, which made the tab impossible to leave: backing out of the feed landed here and
 * was sent straight back in.
 *
 * **Chosen folders** — a checklist of folders and a button. Here the choosing is the point
 * of the mode, so it is asked every time on purpose.
 *
 * The wall itself comes in three presentations — masonry, grid, list — which are three ways
 * of drawing the same list of clips and nothing more. Changing between them re-lays out what
 * is already in memory; it never re-reads MediaStore, and it never touches the thumbnail
 * cache, so switching costs a layout pass rather than a load.
 *
 * The feed is a separate, portrait-locked activity rather than a tab, so it can go properly
 * fullscreen without a bottom bar sitting over the video.
 */
class ShortsTabFragment : Fragment() {

    private var binding: FragmentShortsBinding? = null
    private lateinit var prefs: Prefs

    private var allVideos: List<Video> = emptyList()

    /** The clips currently on show, sorted. Kept so a layout change can re-submit without
     *  going back to MediaStore. */
    private var clips: List<Video> = emptyList()

    /** Folder mode: which folders to draw from. */
    private val folderAdapter = ShortsSourceAdapter { folder, checked ->
        prefs.shortsFolders = if (checked) prefs.shortsFolders + folder.path
        else prefs.shortsFolders - folder.path
        updateCount()
    }

    /** Masonry and grid: the wall. */
    private val tileAdapter = ShortsTileAdapter(
        isFavourite = { video -> prefs.isFavourite(video.id) },
        onClick = { video -> openFeedAt(video) },
    )

    /** List: the same clips as rows, for anyone who wants the names and the sizes. */
    private val rowAdapter = VideoAdapter(
        onClick = { video -> openFeedAt(video) },
        isPinned = { false },
        showFolder = true,
        selectable = false,
    )

    private val wholeDevice: Boolean
        get() = prefs.shortsSource == ShortsSource.WHOLE_DEVICE

    /**
     * What the list is already laid out as.
     *
     * Handing a RecyclerView a new LayoutManager throws away where you were, and onStart runs
     * every time you come back from the feed. Rebuilding it only when the answer has actually
     * changed is the difference between returning to the clip you tapped and returning to the
     * top of a thousand of them.
     */
    private var appliedLayout: ShortsLayout? = null
    private var showingFolders = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        prefs = (requireActivity().application as SeamlessApp).prefs
        val b = FragmentShortsBinding.inflate(inflater, container, false)
        binding = b

        b.play.setOnClickListener { openFeed() }
        b.btnShuffleAll.setOnClickListener { openFeed() }
        b.btnViewOptions.setOnClickListener { showViewOptions() }

        // Set once. Checking a chip in code fires this too, which is why it bails when the
        // value has not actually moved rather than juggling the listener on and off.
        b.filters.setOnCheckedStateChangeListener { _, checked ->
            val wanted = filterFor(checked.firstOrNull() ?: return@setOnCheckedStateChangeListener)
            if (wanted == prefs.shortsFilter) return@setOnCheckedStateChangeListener
            prefs.shortsFilter = wanted
            showClips()
            binding?.list?.scrollToPosition(0)
        }

        // A tile's height is a fraction of a column's width, so the wall cannot be measured
        // until the list has been. Guarded on the value actually changing, or a rebind would
        // trigger the layout that triggered it.
        b.list.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val inner = view.width - view.paddingStart - view.paddingEnd
            if (inner <= 0) return@addOnLayoutChangeListener
            val width = inner / COLUMNS
            if (width == tileAdapter.columnWidth) return@addOnLayoutChangeListener
            tileAdapter.columnWidth = width
            // Posted: a notify during a layout pass is refused by RecyclerView.
            view.post { if (binding != null) tileAdapter.submit(clips) }
        }

        applySourceMode()

        // Room for the floating navigation capsule, given to whichever view actually
        // reaches the bottom of the screen: the wall scrolls under the glass, but the
        // folder mode's button has to sit clear of it. Applied once, from the mode this
        // fragment was built in — changing the mode in Settings and coming back builds a
        // new one.
        if (wholeDevice) b.list.applyFloatingNavInset() else b.footer.applyFloatingNavInset()
        dressTopPanel(b)
        return b.root
    }

    /**
     * The panel the title and quick views sit on, and the room the list leaves for it.
     *
     * The wall runs the full height of the screen, so the status bar's room is the panel's to
     * hold rather than the container's, and the list is padded by however tall the panel turns
     * out to be. Measuring it rather than naming a number keeps the two in step through a
     * rotation, a larger font, and the quick views coming and going with the mode.
     */
    private fun dressTopPanel(b: FragmentShortsBinding) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val style = prefs.navStyle
        b.backdrop.capturing = style != NavStyle.ISLAND
        b.topGlass.corners = GlassView.Corners.BOTTOM
        b.topGlass.source = b.backdrop
        b.topGlass.configure(style, night)
        b.backdrop.onBackdropChanged = { binding?.topGlass?.invalidate() }
        styleChips(style, night)

        ViewCompat.setOnApplyWindowInsetsListener(b.topPanel) { panel, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            panel.updatePadding(top = bars.top)
            insets
        }
        b.topPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) binding?.list?.updatePadding(top = bottom - top)
        }
    }

    /**
     * The quick views, coloured from the navigation style, so both ends of the screen agree
     * about what the app is made of: the accent pill of the frosted bar, the island's filled
     * capsule on a hairline, or liquid glass's clear chip with accent lettering.
     */
    private fun styleChips(style: NavStyle, night: Boolean) {
        val b = binding ?: return
        val context = requireContext()
        val density = resources.displayMetrics.density
        val selection = ContextCompat.getColor(context, R.color.nav_island)
        val onSelection = ContextCompat.getColor(context, R.color.nav_on_island)
        val muted = ContextCompat.getColor(context, R.color.nav_ink_muted)
        val ink = ContextCompat.getColor(context, R.color.nav_ink)
        val accent = ContextCompat.getColor(context, R.color.nav_accent)

        val look = when (style) {
            NavStyle.GLASS -> ChipLook(
                fillOn = selection,
                fillOff = ColorUtils.setAlphaComponent(ink, if (night) 0x1F else 0x12),
                textOn = onSelection,
                textOff = muted,
                strokeOn = Color.TRANSPARENT,
                strokeOff = Color.TRANSPARENT,
                strokeWidth = 0f,
            )
            NavStyle.ISLAND -> ChipLook(
                fillOn = selection,
                fillOff = Color.TRANSPARENT,
                textOn = onSelection,
                textOff = muted,
                strokeOn = Color.TRANSPARENT,
                strokeOff = ColorUtils.setAlphaComponent(muted, 0x4D),
                strokeWidth = density,
            )
            NavStyle.LIQUID -> ChipLook(
                fillOn = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x2E else 0xB8),
                fillOff = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x14 else 0x59),
                // Light glass is nearly white, so the accent is taken a step darker on it.
                textOn = if (night) accent else ColorUtils.blendARGB(accent, Color.BLACK, 0.18f),
                textOff = ColorUtils.setAlphaComponent(ink, 0xCC),
                strokeOn = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x5C else 0xE6),
                strokeOff = ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x1F else 0x80),
                strokeWidth = 1.2f * density,
            )
        }

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        listOf(b.chipAll, b.chipRecent, b.chipFavourites, b.chipLongest).forEach { chip ->
            chip.chipBackgroundColor = ColorStateList(states, intArrayOf(look.fillOn, look.fillOff))
            chip.setTextColor(ColorStateList(states, intArrayOf(look.textOn, look.textOff)))
            chip.chipStrokeColor = ColorStateList(states, intArrayOf(look.strokeOn, look.strokeOff))
            chip.chipStrokeWidth = look.strokeWidth
            // The selection is said by the fill, as it is in the navigation bar; a tick as well
            // is one answer too many, and it makes the chip jump wider when it is chosen.
            chip.isCheckedIconVisible = false
            chip.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 0x1F))
        }
    }

    override fun onStart() {
        super.onStart()
        // Both can have changed elsewhere: the mode in Settings, a favourite in the feed.
        applySourceMode()
        if (MainActivity.hasVideoPermission(requireContext())) load()
    }

    /**
     * Plays the current quick view in a fresh random order.
     *
     * The chip goes with it. Shuffling "everything" from a screen showing eleven favourites
     * would be answering a question nobody asked.
     */
    private fun openFeed() {
        startActivity(ShortsActivity.intent(requireContext(), feedFilter()))
    }

    /** The same feed, starting on the clip that was tapped. */
    private fun openFeedAt(video: Video) {
        startActivity(ShortsActivity.intentAt(requireContext(), video.id, feedFilter()))
    }

    /** Folder mode has no chips, so it can only ever mean everything in those folders. */
    private fun feedFilter(): ShortsFilter =
        if (wholeDevice) prefs.shortsFilter else ShortsFilter.ALL

    // ---- presentation ----

    /**
     * Swaps the tab between its two shapes.
     *
     * The adapter is set here rather than once in [onCreateView] because the two modes put
     * genuinely different things in the same list: folders with checkboxes, or clips.
     */
    private fun applySourceMode() {
        val b = binding ?: return
        b.sourcesHeader.visibility = if (wholeDevice) View.GONE else View.VISIBLE
        b.footer.visibility = if (wholeDevice) View.GONE else View.VISIBLE
        b.toolbarActions.visibility = if (wholeDevice) View.VISIBLE else View.GONE
        // The chips narrow a list of clips; folder mode has a list of folders.
        b.filterBar.visibility = if (wholeDevice) View.VISIBLE else View.GONE
        if (wholeDevice) b.filters.check(chipFor(prefs.shortsFilter))

        if (wholeDevice) {
            showingFolders = false
            applyClipLayout()
        } else if (!showingFolders) {
            showingFolders = true
            appliedLayout = null
            b.list.layoutManager = LinearLayoutManager(requireContext())
            b.list.adapter = folderAdapter
        }
    }

    /**
     * Masonry, grid or list.
     *
     * Only the layout manager and the adapter change; the clips themselves are re-submitted
     * from what is already held. GAP_HANDLING_NONE matters more than it looks: the default
     * lets the staggered manager move an item to another column mid-scroll to close a gap,
     * which reads as the wall rearranging itself under your thumb.
     */
    private fun applyClipLayout() {
        val b = binding ?: return
        applyLayoutIcon()
        if (appliedLayout == prefs.shortsLayout) return
        appliedLayout = prefs.shortsLayout
        tileAdapter.layout = prefs.shortsLayout
        b.list.layoutManager = when (prefs.shortsLayout) {
            ShortsLayout.MASONRY -> StaggeredGridLayoutManager(
                COLUMNS,
                StaggeredGridLayoutManager.VERTICAL,
            ).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE }

            ShortsLayout.GRID -> GridLayoutManager(requireContext(), COLUMNS)
            ShortsLayout.LIST -> LinearLayoutManager(requireContext())
        }
        b.list.adapter =
            if (prefs.shortsLayout == ShortsLayout.LIST) rowAdapter else tileAdapter
    }

    /** The toolbar button wears the layout you are in. */
    private fun applyLayoutIcon() {
        val b = binding ?: return
        b.btnViewOptions.setImageResource(
            when (prefs.shortsLayout) {
                ShortsLayout.MASONRY -> R.drawable.ic_view_masonry
                ShortsLayout.GRID -> R.drawable.ic_view_grid
                ShortsLayout.LIST -> R.drawable.ic_view_list
            }
        )
    }

    private fun showViewOptions() {
        // The sheet is built per opening, so it can be scoped to whichever quick view is
        // selected right now. In folder mode there are no quick views, and All is the scope
        // the folder list has always been sorted by.
        val scope = Prefs.shortsScope(if (wholeDevice) prefs.shortsFilter else ShortsFilter.ALL)
        ViewOptionsSheet(
            prefs = prefs,
            scope = scope,
            keys = SortLabels.VIDEO_KEYS,
            views = listOf(
                ViewChoice(
                    ShortsLayout.MASONRY.name,
                    R.string.view_masonry,
                    R.drawable.ic_view_masonry,
                ),
                ViewChoice(ShortsLayout.GRID.name, R.string.view_grid, R.drawable.ic_view_grid),
                ViewChoice(ShortsLayout.LIST.name, R.string.view_list, R.drawable.ic_view_list),
            ),
            getView = { prefs.shortsLayout.name },
            setView = { prefs.shortsLayout = ShortsLayout.from(it) },
            onViewChanged = {
                // Keep the reading position across a layout change where it means anything.
                val first = firstVisibleClip()
                applyClipLayout()
                showClips()
                if (first > 0) binding?.list?.scrollToPosition(first)
            },
            onSortChanged = { showClips() },
            // Only in whole-device mode: there is nothing to apply "everywhere" to when the
            // chips are not on screen.
            applyToAll = if (wholeDevice) Prefs.allShortsScopes() else emptyList(),
            applyToAllLabel = R.string.sort_apply_all_views,
            scopeLabel = if (wholeDevice) chipLabel(prefs.shortsFilter) else 0,
        ).show(requireContext())
    }

    /**
     * The topmost clip on screen, so switching layout leaves you looking at roughly what you
     * were looking at rather than back at the top of a thousand videos.
     */
    private fun firstVisibleClip(): Int {
        return when (val manager = binding?.list?.layoutManager) {
            is StaggeredGridLayoutManager ->
                manager.findFirstVisibleItemPositions(null).minOrNull() ?: 0

            is LinearLayoutManager -> manager.findFirstVisibleItemPosition()
            else -> 0
        }.coerceAtLeast(0)
    }

    // ---- loading ----

    private fun load() {
        val b = binding ?: return
        b.loading.visibility = View.VISIBLE
        Background.run(
            work = {
                val context = context
                    ?: return@run emptyList<Video>() to emptyList<VideoFolder>()
                val videos = MediaLibrary.queryAll(context, prefs.foldersExcludedFromFeed)
                videos to MediaLibrary.foldersOf(videos)
            },
            then = { (videos, folders) ->
                val current = binding ?: return@run
                current.loading.visibility = View.GONE
                allVideos = videos
                folderAdapter.submit(
                    MediaLibrary.sortFolders(folders, prefs.sortFor(Prefs.SCOPE_LIBRARY)),
                    prefs.shortsFolders,
                )
                showClips()
                updateCount()
            },
        )
    }

    /** Fills the wall. Only whole-device mode has one. */
    private fun showClips() {
        val b = binding ?: return
        if (!wholeDevice) {
            b.empty.visibility = View.GONE
            return
        }
        clips = quickView(ShortsQuery.resolve(allVideos, prefs))
        tileAdapter.submit(clips)
        rowAdapter.submit(clips)

        val empty = clips.isEmpty()
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) applyEmptyText()
        b.count.text = getString(R.string.shorts_video_count, clips.size)
    }

    /**
     * The selected chip, applied.
     *
     * Membership comes from [ShortsQuery.narrow], which the feed calls too; only the order
     * is decided here. Recent narrows as well as orders — ordering alone would have made it
     * identical to All, whose default order is already newest first, and a chip that changes
     * nothing is worse than no chip at all.
     */
    private fun quickView(all: List<Video>): List<Video> {
        val filter = prefs.shortsFilter
        val kept = ShortsQuery.narrow(all, filter, prefs)
        // Each view's own order. Longest still starts longest-first and Recent newest-first —
        // that is their default, not a rule — and either can be re-sorted without dragging the
        // other three along, which is what sharing one setting used to do.
        return MediaLibrary.sortVideos(kept, prefs.sortFor(Prefs.shortsScope(filter)))
    }

    /** An empty wall means different things depending on which chip emptied it. */
    private fun applyEmptyText() {
        val b = binding ?: return
        when (prefs.shortsFilter) {
            ShortsFilter.FAVOURITES -> {
                b.emptyTitle.setText(R.string.shorts_empty_favourites_title)
                b.emptyBody.setText(R.string.shorts_empty_favourites_body)
            }

            ShortsFilter.RECENT -> {
                b.emptyTitle.setText(R.string.shorts_empty_recent_title)
                b.emptyBody.setText(R.string.shorts_empty_recent_body)
            }

            ShortsFilter.ALL, ShortsFilter.LONGEST -> {
                b.emptyTitle.setText(R.string.shorts_empty_title)
                b.emptyBody.setText(R.string.shorts_empty_body)
            }
        }
    }

    /** The quick view's own name, for the heading of the sheet that sorts it. */
    private fun chipLabel(filter: ShortsFilter): Int = when (filter) {
        ShortsFilter.ALL -> R.string.filter_all
        ShortsFilter.RECENT -> R.string.filter_recent
        ShortsFilter.FAVOURITES -> R.string.filter_favourites
        ShortsFilter.LONGEST -> R.string.filter_longest
    }

    private fun chipFor(filter: ShortsFilter): Int = when (filter) {
        ShortsFilter.ALL -> R.id.chip_all
        ShortsFilter.RECENT -> R.id.chip_recent
        ShortsFilter.FAVOURITES -> R.id.chip_favourites
        ShortsFilter.LONGEST -> R.id.chip_longest
    }

    private fun filterFor(chipId: Int): ShortsFilter = when (chipId) {
        R.id.chip_recent -> ShortsFilter.RECENT
        R.id.chip_favourites -> ShortsFilter.FAVOURITES
        R.id.chip_longest -> ShortsFilter.LONGEST
        else -> ShortsFilter.ALL
    }

    /** Folder mode: the count under the title, and whether there is anything to play. */
    private fun updateCount() {
        val b = binding ?: return
        val total = ShortsQuery.resolve(allVideos, prefs).size
        b.play.isEnabled = total > 0
        if (!wholeDevice) b.count.text = getString(R.string.shorts_video_count, total)
    }

    override fun onDestroyView() {
        binding?.list?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        /**
         * Two columns, not three.
         *
         * Three was dense enough that a frame stopped being recognisable, which defeats the
         * point of a wall you are meant to choose from by looking. Two also gives masonry
         * room to be masonry: at three columns the height differences are too small to read
         * as anything but ragged.
         */
        const val COLUMNS = 2
    }
}

/** One navigation style's answer for a quick-view chip, checked and unchecked. */
private class ChipLook(
    val fillOn: Int,
    val fillOff: Int,
    val textOn: Int,
    val textOff: Int,
    val strokeOn: Int,
    val strokeOff: Int,
    val strokeWidth: Float,
)
