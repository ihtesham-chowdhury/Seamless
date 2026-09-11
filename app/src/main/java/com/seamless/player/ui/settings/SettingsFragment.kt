package com.seamless.player.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.AccentColor
import com.seamless.player.data.NavStyle
import com.seamless.player.data.Prefs
import com.seamless.player.data.ShortsSource
import com.seamless.player.data.ThemeMode
import com.seamless.player.data.subtitle.SubtitleStore
import com.seamless.player.databinding.DialogSubtitleAccountBinding
import com.seamless.player.databinding.DialogSubtitleKeyBinding
import com.seamless.player.ui.MainActivity
import com.seamless.player.ui.common.AccentColors
import com.seamless.player.ui.common.AppLock
import com.seamless.player.ui.common.ThemeManager
import com.seamless.player.ui.help.HelpActivity
import com.seamless.player.util.appVersionName
import com.seamless.player.util.applyFloatingNavInset

class SettingsFragment : PreferenceFragmentCompat() {

    private val prefs: Prefs by lazy { (requireActivity().application as SeamlessApp).prefs }

    /**
     * Wraps the preference list in a frame that carries a title, so this tab looks like the
     * other two. PreferenceFragmentCompat builds its own view and offers no way in.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val root = inflater.inflate(R.layout.fragment_settings, container, false) as LinearLayout
        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The cards are the separation now. A divider between two rows of the same card cuts
        // it in half, and one between two cards draws a line across the gap that separates
        // them.
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.applyFloatingNavInset()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Point the preference machinery at the same file Prefs writes to, so the two
        // never disagree about a value.
        preferenceManager.sharedPreferencesName = "settings"
        setPreferencesFromResource(R.xml.settings, rootKey)

        findPreference<ListPreference>("theme_mode")?.setOnPreferenceChangeListener { _, newValue ->
            // AppCompatDelegate recreates every live activity on its own once the mode
            // changes, so there is nothing else to do here.
            ThemeManager.applyNightMode(ThemeMode.from(newValue as String))
            true
        }

        findPreference<Preference>("accent_color")?.setOnPreferenceClickListener {
            showAccentPicker()
            true
        }
        updateAccentSummary()

        findPreference<ListPreference>("nav_style")?.setOnPreferenceChangeListener { _, newValue ->
            // The capsule is right there under this screen, so it changes in place rather than
            // after a restart: the choice is made by looking at it.
            (activity as? MainActivity)?.applyNavStyle(NavStyle.from(newValue as String))
            true
        }

        wireResumeThreshold()

        findPreference<Preference>("help")?.setOnPreferenceClickListener {
            startActivity(HelpActivity.intent(requireContext()))
            true
        }

        findPreference<Preference>("reset_tips")?.setOnPreferenceClickListener {
            prefs.resetTips()
            toast(getString(R.string.settings_reset_tips_done))
            true
        }

        wireShortsSource()
        wireSubtitles()

        findPreference<Preference>("clear_resume")?.setOnPreferenceClickListener {
            prefs.clearAllPositions()
            toast(getString(R.string.settings_clear_resume_done))
            true
        }

        findPreference<Preference>("hidden_folders")?.setOnPreferenceClickListener {
            showHiddenFolders()
            true
        }
        updateHiddenSummary()

        // Refuse to arm a lock that cannot be opened again.
        findPreference<SwitchPreferenceCompat>("app_lock")?.setOnPreferenceChangeListener { pref, value ->
            if (value == true && !AppLock.isAvailable(requireActivity())) {
                toast(getString(R.string.lock_unavailable))
                (pref as SwitchPreferenceCompat).isChecked = false
                false
            } else {
                true
            }
        }

        findPreference<Preference>("locked_folders")?.setOnPreferenceClickListener {
            showLockedFolders()
            true
        }
        updateLockedSummary()

        findPreference<Preference>("version")?.summary = requireContext().appVersionName()
    }

    /**
     * The resume threshold, mirrored from a String-valued preference into the Int the rest
     * of the app reads — the same split as the shorts length, for the same reason.
     *
     * The summary says what the number means rather than only repeating it, so this one does
     * not use the simple summary provider.
     */
    private fun wireResumeThreshold() {
        findPreference<ListPreference>("resume_minutes_label")?.apply {
            value = prefs.resumeThresholdMinutes.toString()
            summary = getString(R.string.settings_resume_summary, prefs.resumeThresholdMinutes)
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = (newValue as String).toIntOrNull() ?: 10
                prefs.resumeThresholdMinutes = minutes
                summary = getString(R.string.settings_resume_summary, minutes)
                true
            }
        }
    }

    // ---- subtitles ----

    /**
     * The three rows that hold something rather than toggle something.
     *
     * All three write through [Prefs] rather than letting the preference machinery persist them:
     * the key and the password live in a separate file that is excluded from backup, and "clear"
     * is an action rather than a value.
     */
    private fun wireSubtitles() {
        findPreference<Preference>("subtitle_api_key")?.setOnPreferenceClickListener {
            showApiKeyDialog()
            true
        }
        findPreference<Preference>("subtitle_account")?.setOnPreferenceClickListener {
            showAccountDialog()
            true
        }
        findPreference<Preference>("subtitle_clear")?.setOnPreferenceClickListener {
            confirmClearSubtitles()
            true
        }
        updateSubtitleSummaries()
    }

    /**
     * Asks before deleting the lot.
     *
     * The row used to be called "Clear downloaded subtitles" and did exactly that on one tap,
     * with nothing to undo it. It is called "Downloaded subtitles" now — a thing you have,
     * with its size, sitting among the other settings — and the destructive half of it lives
     * where a destructive action belongs: behind a question that says what will go.
     */
    private fun confirmClearSubtitles() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_subtitle_clear_action)
            .setMessage(R.string.settings_subtitle_clear_confirm)
            .setPositiveButton(R.string.settings_subtitle_clear_action) { _, _ ->
                SubtitleStore(requireContext()).clear()
                toast(getString(R.string.settings_subtitle_clear_done))
                updateSubtitleSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSubtitleSummaries() {
        findPreference<Preference>("subtitle_api_key")?.setSummary(
            if (prefs.subtitleApiKey.isBlank()) R.string.settings_subtitle_key_missing
            else R.string.settings_subtitle_key_present
        )

        findPreference<Preference>("subtitle_account")?.summary =
            prefs.subtitleAccountName.ifBlank {
                getString(R.string.settings_subtitle_account_none)
            }

        val store = SubtitleStore(requireContext())
        val held = store.count()
        findPreference<Preference>("subtitle_clear")?.summary = if (held == 0) {
            getString(R.string.settings_subtitle_clear_none)
        } else {
            resources.getQuantityString(
                R.plurals.settings_subtitle_clear_summary,
                held,
                held,
                Formatter.formatFileSize(requireContext(), store.totalBytes()),
            )
        }
    }

    private fun showApiKeyDialog() {
        val binding = DialogSubtitleKeyBinding.inflate(layoutInflater)
        binding.explain.text = linkedExplanation()
        binding.explain.movementMethod = LinkMovementMethod.getInstance()
        binding.key.setText(prefs.subtitleApiKey)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_subtitle_key_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val fresh = binding.key.text?.toString().orEmpty().trim()
                if (fresh != prefs.subtitleApiKey) {
                    // A different key is a different consumer, so the session token that came
                    // from the old one is worthless and would only produce a puzzling refusal.
                    prefs.subtitleToken = null
                }
                prefs.subtitleApiKey = fresh
                updateSubtitleSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The explanation, with the registration address as a link.
     *
     * An address to read, remember and type into a browser is two steps too many for something
     * the dialog can simply open. Only the address is tappable, and it opens in the browser: the
     * page is fetched there, not by this app, so nothing about Seamless's networking changes.
     * If the address is ever missing from a translation, the text is shown as it is.
     */
    private fun linkedExplanation(): CharSequence {
        val message = getString(R.string.settings_subtitle_key_message)
        val address = getString(R.string.settings_subtitle_key_link)
        val start = message.indexOf(address)
        if (start < 0) return message
        return SpannableString(message).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = openRegistration()
                },
                start,
                start + address.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun openRegistration() {
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.opensubtitles_consumers_url)))
        try {
            startActivity(page)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.settings_link_no_browser))
        }
    }

    private fun showAccountDialog() {
        val binding = DialogSubtitleAccountBinding.inflate(layoutInflater)
        binding.username.setText(prefs.subtitleAccountName)
        binding.password.setText(prefs.subtitleAccountPassword)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_subtitle_account_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.subtitleAccountName = binding.username.text?.toString().orEmpty()
                prefs.subtitleAccountPassword = binding.password.text?.toString().orEmpty()
                // Signing in as somebody else, or out altogether, invalidates the old token.
                prefs.subtitleToken = null
                updateSubtitleSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLockedSummary() {
        findPreference<Preference>("locked_folders")?.summary =
            getString(R.string.settings_locked_folders_summary, prefs.lockedFolders.size)
    }

    /** Lists what is locked; unlocking anything requires authenticating first. */
    private fun showLockedFolders() {
        val locked = prefs.lockedFolders.sorted()
        if (locked.isEmpty()) {
            toast(getString(R.string.no_locked_folders))
            return
        }
        val names = locked
            .map { path -> path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
            .toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_locked_folders_title)
            .setItems(names) { _, which ->
                AppLock.authenticate(requireActivity(), R.string.lock_remove_prompt_title, onSuccess = {
                    prefs.setFolderLocked(locked[which], false)
                    updateLockedSummary()
                })
            }
            .setNeutralButton(R.string.unlock_all) { _, _ ->
                AppLock.authenticate(requireActivity(), R.string.lock_remove_prompt_title, onSuccess = {
                    prefs.lockedFolders = emptySet()
                    updateLockedSummary()
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAccentSummary() {
        findPreference<Preference>("accent_color")?.summary =
            getString(AccentColors.label(prefs.accentColor))
    }

    /**
     * A grid of colour circles, the same idea as MX Player's accent picker. Unlike the
     * theme mode above, an accent change does not take effect on its own — a theme
     * overlay only applies while an activity's views are being inflated — so picking one
     * here recreates the activity.
     */
    private fun showAccentPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_accent_picker, null)
        val grid = view.findViewById<GridLayout>(R.id.grid)
        val current = prefs.accentColor

        lateinit var dialog: AlertDialog

        AccentColor.entries.forEach { accent ->
            val item = layoutInflater.inflate(R.layout.item_accent_swatch, grid, false)
            val swatch = item.findViewById<View>(R.id.swatch)
            val check = item.findViewById<View>(R.id.check)

            val color = ContextCompat.getColor(requireContext(), AccentColors.previewColorRes(accent))
            (swatch.background.mutate() as GradientDrawable).setColor(color)
            check.visibility = if (accent == current) View.VISIBLE else View.GONE
            item.contentDescription = getString(AccentColors.label(accent))

            item.setOnClickListener {
                prefs.accentColor = accent
                dialog.dismiss()
                requireActivity().recreate()
            }
            grid.addView(item)
        }

        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_accent_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun updateHiddenSummary() {
        findPreference<Preference>("hidden_folders")?.summary =
            getString(R.string.settings_hidden_folders_summary, prefs.hiddenFolders.size)
    }

    /** Lists what is hidden; tapping an entry brings that folder back. */
    private fun showHiddenFolders() {
        val hidden = prefs.hiddenFolders.sorted()
        if (hidden.isEmpty()) {
            toast(getString(R.string.no_hidden_folders))
            return
        }
        val names = hidden
            .map { path -> path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
            .toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_hidden_folders_title)
            .setItems(names) { _, which ->
                prefs.unhideFolder(hidden[which])
                updateHiddenSummary()
            }
            .setNeutralButton(R.string.unhide_all) { _, _ ->
                prefs.hiddenFolders = emptySet()
                updateHiddenSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The shorts source, and the two filters that only mean anything in whole-device mode.
     *
     * The length cut-off needs mirroring by hand: its preference stores a String, because
     * that is all a ListPreference can store, while the rest of the app reads the setting as
     * an Int. Writing through to the real key here keeps one of them from surprising the
     * other later.
     */
    private fun wireShortsSource() {
        val landscape = findPreference<SwitchPreferenceCompat>("shorts_include_short_landscape")
        val length = findPreference<ListPreference>("shorts_max_landscape_label")

        fun applyEnabled() {
            val wholeDevice = prefs.shortsSource == ShortsSource.WHOLE_DEVICE
            landscape?.isEnabled = wholeDevice
            length?.isEnabled = wholeDevice && prefs.includeShortLandscape
        }

        findPreference<ListPreference>("shorts_source")?.apply {
            // The preference file may predate this screen, in which case the stored value is
            // whatever the old Shorts tab last wrote — or nothing at all.
            value = prefs.shortsSource.name
            setOnPreferenceChangeListener { _, _ ->
                // The stored value updates before this returns true, so read it afterwards.
                view?.post { applyEnabled() }
                true
            }
        }

        landscape?.setOnPreferenceChangeListener { _, _ ->
            view?.post { applyEnabled() }
            true
        }

        length?.apply {
            value = prefs.maxLandscapeSeconds.toString()
            setOnPreferenceChangeListener { _, newValue ->
                prefs.maxLandscapeSeconds = (newValue as String).toIntOrNull() ?: 30
                true
            }
        }

        applyEnabled()
    }

    private fun toast(text: String) =
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
}
