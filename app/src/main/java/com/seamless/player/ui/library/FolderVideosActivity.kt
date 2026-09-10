package com.seamless.player.ui.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.LibraryView
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.Prefs
import com.seamless.player.data.Video
import com.seamless.player.databinding.ActivityFolderVideosBinding
import com.seamless.player.ui.common.MediaOps
import com.seamless.player.ui.common.SafMover
import com.seamless.player.ui.common.SearchField
import com.seamless.player.ui.common.SortLabels
import com.seamless.player.ui.common.Tips
import com.seamless.player.ui.common.ThemeManager
import com.seamless.player.ui.common.ViewChoice
import com.seamless.player.ui.common.ViewOptionsSheet
import com.seamless.player.ui.player.PlayerActivity
import com.seamless.player.util.Background
import com.seamless.player.util.applyBottomAndSideInsets
import com.seamless.player.util.applyTopSystemInset

/** The videos inside one folder, including everything in its subfolders. */
class FolderVideosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderVideosBinding
    private lateinit var prefs: Prefs
    private lateinit var mediaOps: MediaOps

    /** Unsorted; the sorted copy is handed to the adapter. */
    private var videos: List<Video> = emptyList()

    /** Every folder on the device, for the move destination picker. */
    private var allFolderPaths: List<String> = emptyList()

    /** Current search text; empty means show everything. */
    private var query: String = ""

    /** This folder's MediaStore path, which is also its sort scope. */
    private val folderPath: String
        get() = intent.getStringExtra(EXTRA_PATH).orEmpty()

    private lateinit var searchField: SearchField

    private val adapter = VideoAdapter(
        onClick = { video -> startActivity(PlayerActivity.intent(this, video)) },
        onSelectionChanged = { updateToolbarForSelection() },
        isPinned = { video -> prefs.isVideoPinned(video.id) },
    )

    /**
     * Carries the system's write / delete consent dialog. Scoped storage will not let us
     * touch media we did not create until the user has agreed.
     */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        mediaOps.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    /** Videos waiting on a destination folder from the picker below. */
    private var pendingSafMove: List<Video> = emptyList()

    /** The Storage Access Framework destination picker, used when MediaStore refuses a move. */
    private val folderAccessLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val videos = pendingSafMove
        pendingSafMove = emptyList()
        if (treeUri == null || videos.isEmpty()) return@registerForActivityResult

        // Hold on to the grant so a later move into the same folder needs no second ask.
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        Background.run(
            work = { SafMover.copyInto(this, treeUri, videos) },
            then = { (result, copied) ->
                if (result.moved == 0) {
                    Toast.makeText(
                        this,
                        result.problem ?: getString(R.string.move_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@run
                }
                // Copies are in place; now remove the originals so this is a move.
                mediaOps.deleteAfterCopy(copied, result.moved)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyAccent(this, (application as SeamlessApp).prefs.accentColor)
        super.onCreate(savedInstanceState)
        binding = ActivityFolderVideosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = (application as SeamlessApp).prefs

        // Pad the column, not the toolbar. A MaterialToolbar has a fixed height, so padding
        // it does not make it taller — it just pushes the title and menu icons down out of
        // their own bounds and onto whatever sits below, which here is the search field.
        binding.root.applyTopSystemInset()
        binding.list.applyBottomAndSideInsets()

        mediaOps = MediaOps(this, consentLauncher).apply {
            onChanged = { message ->
                Toast.makeText(this@FolderVideosActivity, message, Toast.LENGTH_LONG).show()
                adapter.clearSelection()
                load()
            }
            onMoveNeedsFolderAccess = { videos, reason -> offerFolderAccess(videos, reason) }
        }

        binding.toolbar.setNavigationOnClickListener {
            when {
                adapter.inSelectionMode -> adapter.clearSelection()
                searchField.isOpen -> searchField.close()
                else -> finish()
            }
        }
        binding.toolbar.setOnMenuItemClickListener { item -> onMenuItem(item.itemId) }
        wireToolbarActions()

        binding.list.adapter = adapter
        searchField = SearchField(binding.searchBar) { text ->
            query = text
            showSorted()
        }
        updateToolbarForSelection()
        applyViewMode()
        load()
        Tips.showOnce(this, prefs, Tips.FOLDER, R.string.tip_folder)
    }

    private fun wireToolbarActions() {
        binding.btnSearch.setOnClickListener { searchField.toggle() }
        binding.btnViewOptions.setOnClickListener { showViewOptions() }
        // Shuffle play, not a shuffle setting. It used to flip a preference and say so in a
        // toast, which answered a question nobody pressing a shuffle button is asking: they want
        // the folder playing, in a random order, now. It starts from a random video with shuffle
        // on for that session only, and leaves the player's own toggle as it was, so tapping a
        // video afterwards plays in whatever order that toggle says.
        binding.btnShuffle.setOnClickListener {
            val start = videos.randomOrNull() ?: return@setOnClickListener
            startActivity(PlayerActivity.intent(this, start, shuffle = true))
        }
    }

    private fun onMenuItem(id: Int): Boolean = when (id) {
        R.id.action_rename -> { promptRename(); true }
        R.id.action_move -> { promptMove(); true }
        R.id.action_delete -> { promptDelete(); true }
        R.id.action_share_selected -> { shareSelected(); true }
        R.id.action_pin -> { togglePinOnSelected(); true }
        else -> false
    }

    // ---- toolbar ----

    /** Swaps the toolbar between its normal menu and the selection menu. */
    private fun updateToolbarForSelection() {
        val count = adapter.selection.size
        binding.toolbar.menu.clear()
        if (count == 0) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.title = intent.getStringExtra(EXTRA_NAME).orEmpty()
            binding.toolbarActions.visibility = View.VISIBLE
        } else {
            binding.toolbarActions.visibility = View.GONE
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.title = getString(R.string.selected_count, count)
            binding.toolbar.inflateMenu(R.menu.video_selection)
            // Renaming more than one file at a time has no sensible meaning.
            binding.toolbar.menu.findItem(R.id.action_rename)?.isVisible = count == 1
            // One label for both directions, chosen by what is already pinned: a mixed
            // selection pins the lot, an all-pinned one releases it.
            binding.toolbar.menu.findItem(R.id.action_pin)?.setTitle(
                if (adapter.selection.all { prefs.isVideoPinned(it) }) R.string.action_unpin
                else R.string.action_pin
            )
        }
    }

    /** Pins the chosen videos, or releases them if every one was pinned already. */
    private fun togglePinOnSelected() {
        val chosen = adapter.selection.toList()
        if (chosen.isEmpty()) return
        val allPinned = chosen.all { prefs.isVideoPinned(it) }
        prefs.setVideosPinned(chosen, !allPinned)
        adapter.clearSelection()
        showSorted()
    }

    // ---- file operations ----

    private fun promptRename() {
        val video = adapter.selectedVideos().singleOrNull() ?: return
        val input = EditText(this).apply {
            setText(video.name)
            setSelection(0, video.name.substringBeforeLast('.').length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != video.name) mediaOps.rename(video, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * MediaStore refused. Explain why in a sentence, then offer the route that works:
     * pick the destination folder directly, which grants access SAF-style and sidesteps
     * MediaStore's rules about which directories a video may live in.
     */
    private fun offerFolderAccess(videos: List<Video>, reason: String) {
        pendingSafMove = videos
        AlertDialog.Builder(this)
            .setTitle(R.string.move_needs_access_title)
            .setMessage(getString(R.string.move_needs_access_message, reason))
            .setPositiveButton(R.string.move_choose_folder) { _, _ ->
                runCatching { folderAccessLauncher.launch(null) }
                    .onFailure {
                        Toast.makeText(this, R.string.no_folder_picker, Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingSafMove = emptyList() }
            .show()
    }

    private fun promptMove() {
        val chosen = adapter.selectedVideos()
        if (chosen.isEmpty()) return
        if (allFolderPaths.isEmpty()) {
            Toast.makeText(this, R.string.no_move_targets, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = allFolderPaths.map { it.trimEnd('/') }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to)
            .setItems(labels) { _, which -> mediaOps.move(chosen, allFolderPaths[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptDelete() {
        val chosen = adapter.selectedVideos()
        if (chosen.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_confirm, chosen.size))
            .setPositiveButton(R.string.action_delete) { _, _ -> mediaOps.delete(chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareSelected() {
        val chosen = adapter.selectedVideos()
        if (chosen.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(chosen.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_video)))
    }

    // ---- loading and presentation ----

    private fun load() {
        val path = folderPath
        Background.run(
            work = {
                val all = MediaLibrary.queryAll(this, prefs.hiddenFolders)
                MediaLibrary.videosUnder(all, setOf(path)) to
                    MediaLibrary.foldersOf(all).map { it.path }.filter { it != path }.sorted()
            },
            then = { (inFolder, others) ->
                videos = inFolder
                allFolderPaths = others
                showSorted()
            },
        )
    }

    private fun showSorted() {
        val matching =
            if (query.isBlank()) videos
            else videos.filter { it.name.contains(query, ignoreCase = true) }
        val sorted = MediaLibrary.sortVideos(matching, prefs.sortFor(folderPath))
        adapter.submit(MediaLibrary.pinnedFirst(sorted) { prefs.isVideoPinned(it.id) })
    }

    private fun applyViewMode() {
        val mode = prefs.libraryView
        adapter.viewMode = mode
        binding.list.layoutManager =
            if (mode == LibraryView.GRID) GridLayoutManager(this, GRID_SPAN)
            else LinearLayoutManager(this)
    }

    /**
     * The order belongs to this folder, not to the app.
     *
     * A camera roll wants newest first and a folder of clips wants no order at all, and one
     * global setting cannot be both. The scope is the folder's own MediaStore path, so the
     * choice survives leaving and coming back.
     */
    private fun showViewOptions() {
        ViewOptionsSheet(
            prefs = prefs,
            scope = folderPath,
            keys = SortLabels.VIDEO_KEYS,
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
        ).show(this)
    }

    override fun onBackPressed() {
        // Back should leave the selection before it leaves the folder.
        if (adapter.inSelectionMode) {
            adapter.clearSelection()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        private const val GRID_SPAN = 2
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, path: String, name: String): Intent =
            Intent(context, FolderVideosActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_NAME, name)
    }
}
