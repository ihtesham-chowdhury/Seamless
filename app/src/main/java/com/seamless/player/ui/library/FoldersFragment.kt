package com.seamless.player.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.LibraryView
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.Prefs
import com.seamless.player.data.Video
import com.seamless.player.data.VideoFolder
import com.seamless.player.databinding.FragmentFoldersBinding
import com.seamless.player.ui.MainActivity
import com.seamless.player.ui.common.AppLock
import com.seamless.player.ui.common.SearchField
import com.seamless.player.ui.common.Tips
import com.seamless.player.ui.common.SortLabels
import com.seamless.player.ui.common.ViewChoice
import com.seamless.player.ui.common.ViewOptionsSheet
import com.seamless.player.ui.player.PlayerActivity
import com.seamless.player.util.Background
import com.seamless.player.util.applyFloatingNavInset

/** Every folder on the device that contains at least one video. */
class FoldersFragment : Fragment() {

    private var binding: FragmentFoldersBinding? = null
    private lateinit var prefs: Prefs

    /** Unsorted; the sorted copy is handed to the adapter. */
    private var folders: List<VideoFolder> = emptyList()

    /**
     * Every video behind those folders, kept from the same query. The thumbnail picker
     * needs a folder's contents, and re-querying MediaStore to get them would be a second
     * full scan for something we already have in hand.
     */
    private var allVideos: List<Video> = emptyList()

    /** Current search text; empty means show everything. */
    private var query: String = ""

    private val adapter = FolderAdapter(
        onOpen = { folder -> openFolder(folder) },
        onSelectionChanged = { updateToolbarForSelection() },
        isLocked = { folder -> prefs.isFolderLocked(folder.path) },
        isPinned = { folder -> prefs.isFolderPinned(folder.path) },
    )

    /**
     * Videos matching the query, from anywhere in the library.
     *
     * Searching the library used to match folder *names* only, so a file you could see
     * perfectly well inside a folder was unfindable from the screen above it. This runs over
     * every video the library already holds in memory, which is why it costs nothing extra.
     *
     * Locked folders are excluded. Their contents are meant to be invisible without
     * authentication, and a search box that lists them by name would undo that completely.
     */
    private val results = VideoAdapter(
        onClick = { video -> startActivity(PlayerActivity.intent(requireContext(), video)) },
        isPinned = { video -> prefs.isVideoPinned(video.id) },
        showFolder = true,
        selectable = false,
    )

    private var searchField: SearchField? = null

    /** A locked folder asks for a fingerprint, face or the device PIN before it opens. */
    private fun openFolder(folder: VideoFolder) {
        val open = {
            startActivity(
                FolderVideosActivity.intent(requireContext(), folder.path, folder.name)
            )
        }
        if (prefs.isFolderLocked(folder.path)) {
            AppLock.forFolder(requireActivity(), folder.name, onSuccess = open)
        } else {
            open()
        }
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else showPermissionPanel()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        prefs = (requireActivity().application as SeamlessApp).prefs
        val b = FragmentFoldersBinding.inflate(inflater, container, false)
        binding = b

        // Two sections in one list: the folders, then any videos the query turned up.
        b.list.adapter = ConcatAdapter(adapter, results)
        b.list.applyFloatingNavInset()
        b.grant.setOnClickListener { requestPermission.launch(MainActivity.videoPermission) }

        b.btnSearch.setOnClickListener { searchField?.toggle() }
        b.btnViewOptions.setOnClickListener { showViewOptions() }
        b.btnLastPlayed.setOnClickListener { openLastPlayed() }

        // Only the selection menu is left in the menu; everything else is in the capsule.
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_hide -> {
                    hideSelected()
                    true
                }
                R.id.action_lock -> {
                    toggleLockOnSelected()
                    true
                }
                R.id.action_change_cover -> {
                    changeCoverOfSelected()
                    true
                }
                R.id.action_pin -> {
                    togglePinOnSelected()
                    true
                }
                else -> false
            }
        }
        b.toolbar.setNavigationOnClickListener { adapter.clearSelection() }
        b.searchBar.search.setHint(R.string.search_hint_global)
        searchField = SearchField(b.searchBar) { text ->
            query = text
            showSorted()
        }

        // Back closes whatever is open before it leaves the screen: a selection first,
        // then search. Without this, Back from an open search box exits the app, which is a
        // long way to fall for wanting to stop typing.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            when {
                adapter.inSelectionMode -> adapter.clearSelection()
                searchField?.isOpen == true -> searchField?.close()
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        updateToolbarForSelection()
        applyViewMode()
        return b.root
    }

    override fun onStart() {
        super.onStart()
        // Grid or list is one setting for the whole library, and it can have been changed
        // inside a folder since this screen was built.
        applyViewMode()
        if (MainActivity.hasVideoPermission(requireContext())) {
            load()
            // Only once there is something to long-press. A hint about folders shown over an
            // empty screen, or over a permission prompt, is noise.
            Tips.showOnce(
                requireActivity(),
                prefs,
                Tips.LIBRARY,
                R.string.tip_library,
                Tips.Room.NAV_PILL,
            )
        } else {
            requestPermission.launch(MainActivity.videoPermission)
        }
    }

    // ---- toolbar ----

    /** Swaps the toolbar between its normal menu and the selection menu. */
    private fun updateToolbarForSelection() {
        val b = binding ?: return
        val count = adapter.selection.size
        b.toolbar.menu.clear()
        if (count == 0) {
            b.toolbar.navigationIcon = null
            b.toolbar.setTitle(R.string.tab_library)
            b.toolbarActions.visibility = View.VISIBLE
        } else {
            b.toolbarActions.visibility = View.GONE
            b.toolbar.setNavigationIcon(R.drawable.ic_back)
            b.toolbar.title = getString(R.string.selected_count, count)
            b.toolbar.inflateMenu(R.menu.library_selection)
            // "Which frame?" only has an answer for one folder, and a locked folder must
            // not display a frame at all.
            val single = adapter.selection.singleOrNull()
            b.toolbar.menu.findItem(R.id.action_change_cover)?.isVisible =
                single != null && !prefs.isFolderLocked(single)
            // One label for both directions, chosen by what is already pinned.
            b.toolbar.menu.findItem(R.id.action_pin)?.setTitle(
                if (adapter.selection.all { prefs.isFolderPinned(it) }) R.string.action_unpin
                else R.string.action_pin
            )
        }
    }

    /**
     * Locks the chosen folders, or unlocks them if they were all locked already. Unlocking
     * is itself gated, otherwise the lock would be trivial to remove.
     */
    private fun toggleLockOnSelected() {
        val chosen = adapter.selection.toSet()
        if (chosen.isEmpty()) return
        val allLocked = chosen.all { prefs.isFolderLocked(it) }

        val apply = {
            chosen.forEach { prefs.setFolderLocked(it, !allLocked) }
            Toast.makeText(
                requireContext(),
                if (allLocked) R.string.folders_unlocked else R.string.folders_locked,
                Toast.LENGTH_SHORT,
            ).show()
            adapter.clearSelection()
            showSorted()
        }

        if (allLocked) {
            AppLock.authenticate(
                requireActivity(),
                R.string.lock_remove_prompt_title,
                onSuccess = apply,
            )
        } else if (!AppLock.isAvailable(requireActivity())) {
            // Locking with nothing to unlock with would strand the folders.
            Toast.makeText(requireContext(), R.string.lock_unavailable, Toast.LENGTH_LONG).show()
        } else {
            apply()
        }
    }

    private fun hideSelected() {
        val chosen = adapter.selection.toSet()
        if (chosen.isEmpty()) return
        prefs.hideFolders(chosen)
        Toast.makeText(
            requireContext(),
            getString(R.string.folders_hidden, chosen.size),
            Toast.LENGTH_SHORT,
        ).show()
        adapter.clearSelection()
        load()
    }

    /** Pins the chosen folders, or releases them if every one was pinned already. */
    private fun togglePinOnSelected() {
        val chosen = adapter.selection.toList()
        if (chosen.isEmpty()) return
        val allPinned = chosen.all { prefs.isFolderPinned(it) }
        prefs.setFoldersPinned(chosen, !allPinned)
        adapter.clearSelection()
        showSorted()
    }

    /**
     * Lets one folder borrow its thumbnail from any video inside it. Offered for a single
     * folder only — "which frame" has no answer for several at once — and never for a
     * locked one, whose whole point is to show nothing of what it contains.
     */
    private fun changeCoverOfSelected() {
        val path = adapter.selection.singleOrNull() ?: return
        val folder = folders.firstOrNull { it.path == path } ?: return
        val contents = MediaLibrary.videosUnder(allVideos, setOf(path))
        if (contents.isEmpty()) return

        CoverPicker.show(
            context = requireContext(),
            folderName = folder.name,
            videos = contents,
            chosenId = prefs.folderCover(path),
        ) { chosen ->
            if (chosen == null) prefs.clearFolderCover(path) else prefs.setFolderCover(path, chosen.id)
            adapter.clearSelection()
            load()
        }
    }

    /** Straight back into the last thing that was watched, queue and all. */
    private fun openLastPlayed() {
        val folder = prefs.lastPlayedFolder
        val id = prefs.lastPlayedId
        if (folder == null || id == 0L) {
            Toast.makeText(requireContext(), R.string.no_last_played, Toast.LENGTH_SHORT).show()
            return
        }

        val open = {
            startActivity(PlayerActivity.intent(requireContext(), folder, id))
        }
        // A locked folder stays locked however you arrive at it, including by this shortcut.
        if (prefs.isFolderLocked(folder)) {
            AppLock.forFolder(requireActivity(), prefs.lastPlayedName.orEmpty(), onSuccess = open)
        } else {
            open()
        }
    }

    // ---- view mode and sorting ----

    private fun applyViewMode() {
        val b = binding ?: return
        val mode = prefs.libraryView
        adapter.viewMode = mode
        results.viewMode = mode
        b.list.layoutManager =
            if (mode == LibraryView.GRID) GridLayoutManager(requireContext(), GRID_SPAN)
            else LinearLayoutManager(requireContext())
    }

    private fun showViewOptions() {
        ViewOptionsSheet(
            prefs = prefs,
            scope = Prefs.SCOPE_LIBRARY,
            keys = SortLabels.FOLDER_KEYS,
            views = listOf(
                ViewChoice(LibraryView.GRID.name, R.string.view_grid, R.drawable.ic_view_grid),
                ViewChoice(LibraryView.LIST.name, R.string.view_list, R.drawable.ic_view_list),
            ),
            getView = { prefs.libraryView.name },
            setView = { prefs.libraryView = LibraryView.from(it) },
            onViewChanged = {
                applyViewMode()
                showSorted()
            },
            onSortChanged = { showSorted() },
        ).show(requireContext())
    }

    // ---- loading ----

    private fun load() {
        val b = binding ?: return
        b.permissionPanel.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        Background.run(
            work = {
                val context = context ?: return@run emptyList<Video>()
                MediaLibrary.queryAll(context, prefs.hiddenFolders)
            },
            then = { videos ->
                val current = binding ?: return@run
                current.loading.visibility = View.GONE
                allVideos = videos
                folders = MediaLibrary.foldersOf(videos) { path -> prefs.folderCover(path) }
                showSorted()
            },
        )
    }

    private fun showSorted() {
        val b = binding ?: return
        val text = query.trim()

        val matchingFolders =
            if (text.isBlank()) folders
            else folders.filter { it.name.contains(text, ignoreCase = true) }
        val sort = prefs.sortFor(Prefs.SCOPE_LIBRARY)
        val sorted = MediaLibrary.sortFolders(matchingFolders, sort)
        adapter.submit(MediaLibrary.pinnedFirst(sorted) { prefs.isFolderPinned(it.path) })

        val matchingVideos = if (text.isBlank()) {
            emptyList()
        } else {
            allVideos.filter {
                it.name.contains(text, ignoreCase = true) && !prefs.isFolderLocked(it.relativePath)
            }
        }
        // Search results borrow the library's own order. A key that means nothing for a
        // video — the number of videos in it — falls back to date inside sortVideos.
        results.submit(MediaLibrary.sortVideos(matchingVideos, sort))

        val nothing = sorted.isEmpty() && matchingVideos.isEmpty()
        b.empty.text =
            if (text.isBlank()) getString(R.string.no_videos)
            else getString(R.string.search_no_results, text)
        b.empty.visibility = if (nothing) View.VISIBLE else View.GONE
    }

    private fun showPermissionPanel() {
        val b = binding ?: return
        b.loading.visibility = View.GONE
        b.permissionPanel.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        searchField = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val GRID_SPAN = 2
    }
}
