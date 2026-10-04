package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.HapticUtils
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Material Expressive bottom sheet that shows what changed since the user's last update.
 * Displays the newest release in full with formatted Markdown, then surfaces older releases
 * as tappable chips so users can browse history without the dialog becoming overwhelming.
 */
class ChangelogDialogFragment : BottomSheetDialogFragment() {
    data class ReleaseItem(
        val tag: String,
        val date: String,
        val body: String,
        val isNew: Boolean = false,
    )

    companion object {
        const val TAG = "ChangelogDialogFragment"
        private const val ARG_RELEASES_JSON = "releases_json"
        private const val ARG_TAG_NAME = "tag_name"

        fun newInstance(
            releases: List<UpdateChecker.ReleaseEntry>,
            currentTagName: String,
        ): ChangelogDialogFragment =
            ChangelogDialogFragment().apply {
                val arr = JSONArray()
                releases.forEach { r ->
                    arr.put(
                        JSONObject().apply {
                            put("tag", r.tagName)
                            put("date", r.publishedAt)
                            put("body", r.body)
                            put("is_new", r.isNew)
                        },
                    )
                }
                arguments =
                    Bundle().apply {
                        putString(ARG_RELEASES_JSON, arr.toString())
                        putString(ARG_TAG_NAME, currentTagName)
                    }
            }

        private val COMMIT_HASH_SUFFIX = Regex("""\s+\([0-9a-f]{7,8}\)$""", RegexOption.MULTILINE)
        private val CC_PREFIX =
            Regex(
                """^(fix|feat|chore|refactor|perf|test|docs|build|ci|style|revert)(\([^)]+\))?:\s*""",
                RegexOption.IGNORE_CASE,
            )

        private fun stripConventionalPrefixes(text: String): String =
            text.lines().joinToString("\n") { line ->
                val bulletEnd =
                    Regex("""^[-*]\s+""")
                        .find(line)
                        ?.range
                        ?.last
                        ?.plus(1)
                        ?: return@joinToString line
                val bullet = line.substring(0, bulletEnd)
                val rest = line.substring(bulletEnd)
                val stripped = CC_PREFIX.replaceFirst(rest, "")
                if (stripped == rest) {
                    line
                } else {
                    bullet + stripped.replaceFirstChar { it.uppercase() }
                }
            }

        fun formatNotes(rawNotes: String): String =
            rawNotes
                .substringBefore("## 📦 Recent Releases")
                .replace(COMMIT_HASH_SUFFIX, "")
                .let { stripConventionalPrefixes(it) }
                .trim()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_changelog, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val tagName = arguments?.getString(ARG_TAG_NAME) ?: ""
        val releases = parseReleases(arguments?.getString(ARG_RELEASES_JSON))

        var currentSelectedTag = tagName
        val versionText = view.findViewById<TextView>(R.id.version_text)
        val notesView = view.findViewById<TextView>(R.id.notes_text)
        val notesScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.notes_scroll)
        val earlierScroll = view.findViewById<HorizontalScrollView>(R.id.earlier_scroll)
        val chipGroup = view.findViewById<ChipGroup>(R.id.earlier_chip_group)
        val earlierSection = view.findViewById<LinearLayout>(R.id.earlier_section)
        val earlierSectionTitle = view.findViewById<TextView>(R.id.earlier_section_title)
        val btnGithub = view.findViewById<MaterialButton>(R.id.btn_github)

        val newReleases = releases.filter { it.isNew }

        var selectAndDisplayTag: (String) -> Unit = {}

        // Configure Markwon with deep-link resolution for release hyperlinks
        val markwon =
            Markwon
                .builder(requireContext())
                .usePlugin(
                    object : AbstractMarkwonPlugin() {
                        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                            builder.linkResolver { _, link ->
                                val releaseTagRegex =
                                    Regex(
                                        """(?:github\.com/[^/]+/[^/]+/releases/tag/|/releases/tag/)([^/?#\s]+)""",
                                    )
                                val match = releaseTagRegex.find(link)
                                if (match != null) {
                                    val tag = match.groupValues[1]
                                    selectAndDisplayTag(tag)
                                    return@linkResolver
                                }
                                if (link.contains("github.com") && link.contains("/releases")) {
                                    earlierSection.takeIf { it.isVisible }?.let {
                                        notesScroll?.fullScroll(View.FOCUS_DOWN)
                                        HapticUtils.segmentTick(notesView)
                                        return@linkResolver
                                    }
                                }
                                try {
                                    CustomTabsHelper.launchUrlOrCopy(requireContext(), link)
                                } catch (e: Exception) {
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                    } catch (e2: Exception) {
                                        Timber.w(e2, "Failed to open link: $link")
                                    }
                                }
                            }
                        }
                    },
                ).build()

        fun displayRelease(
            release: ReleaseItem,
            isCombined: Boolean = false,
        ) {
            currentSelectedTag =
                if (isCombined) {
                    releases.firstOrNull()?.tag ?: tagName
                } else {
                    release.tag
                }

            if (isCombined) {
                versionText.text = getString(R.string.changelog_all_new_updates, newReleases.size)
            } else {
                val formattedDate = UpdateChecker.formatPublishedDate(release.date)
                versionText.text =
                    if (formattedDate.isNotBlank() && formattedDate != release.date) {
                        "${release.tag} · $formattedDate"
                    } else {
                        release.tag
                    }
            }

            val formatted =
                if (isCombined) {
                    release.body
                } else {
                    release.body.takeIf { it.isNotBlank() }?.let { formatNotes(it) }
                }

            notesView.alpha = 0f
            if (formatted != null && formatted.isNotBlank()) {
                markwon.setMarkdown(notesView, formatted)
            } else {
                notesView.setText(R.string.changelog_fallback_message)
            }
            notesView
                .animate()
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            notesScroll?.scrollTo(0, 0)
        }

        // When the user missed multiple updates since last seen, synthesize a combined entry
        val combinedNewRelease: ReleaseItem? =
            if (newReleases.size > 1) {
                val combinedBody =
                    newReleases.joinToString("\n\n---\n\n") { r ->
                        val formattedDate = UpdateChecker.formatPublishedDate(r.date)
                        val header =
                            if (formattedDate.isNotBlank() && formattedDate != r.date) {
                                "### ${r.tag} · $formattedDate"
                            } else {
                                "### ${r.tag}"
                            }
                        val clean = formatNotes(r.body)
                        "$header\n\n$clean"
                    }
                ReleaseItem(
                    tag = getString(R.string.changelog_all_new_updates, newReleases.size),
                    date = newReleases.first().date,
                    body = combinedBody,
                    isNew = true,
                )
            } else {
                null
            }

        // Populate earlier / version history chips
        if (releases.size > 1 || combinedNewRelease != null) {
            earlierSection.isVisible = true
            chipGroup.isSingleSelection = true
            chipGroup.isSelectionRequired = true

            earlierSectionTitle?.setText(
                if (newReleases.isNotEmpty()) {
                    R.string.changelog_version_history
                } else {
                    R.string.changelog_earlier_releases
                },
            )

            // 1. "All New" chip if multiple new releases
            if (combinedNewRelease != null) {
                val combinedChip =
                    Chip(requireContext()).apply {
                        text = combinedNewRelease.tag
                        isCheckable = true
                        isChecked = true
                        chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_autorenew)
                        isChipIconVisible = true
                        setEnsureMinTouchTargetSize(true)
                        setOnClickListener {
                            HapticUtils.segmentTick(this)
                            displayRelease(combinedNewRelease, isCombined = true)
                        }
                    }
                chipGroup.addView(combinedChip)
            }

            // 2. Individual release chips
            releases.forEachIndexed { index, release ->
                val chip =
                    Chip(requireContext()).apply {
                        text =
                            if (release.isNew) {
                                "${release.tag} • ${getString(R.string.changelog_tag_new)}"
                            } else {
                                release.tag
                            }
                        isCheckable = true
                        isChecked = (combinedNewRelease == null && index == 0)
                        if (release.isNew) {
                            chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_bolt_24)
                            isChipIconVisible = true
                        }
                        setEnsureMinTouchTargetSize(true)
                        setOnClickListener {
                            HapticUtils.segmentTick(this)
                            displayRelease(release)
                        }
                    }
                chipGroup.addView(chip)
            }
        } else {
            earlierSection.isVisible = false
        }

        // Tag selection implementation (used by Markwon link resolver)
        selectAndDisplayTag = { targetTag ->
            val cleanTarget = targetTag.removePrefix("v").trim()
            val foundIndex =
                releases.indexOfFirst {
                    it.tag
                        .removePrefix("v")
                        .trim()
                        .equals(cleanTarget, ignoreCase = true)
                }
            if (foundIndex >= 0) {
                val targetRelease = releases[foundIndex]
                val chipIndex = if (combinedNewRelease != null) foundIndex + 1 else foundIndex
                if (chipIndex < chipGroup.childCount) {
                    val targetChip = chipGroup.getChildAt(chipIndex) as? Chip
                    targetChip?.isChecked = true
                    targetChip?.let { earlierScroll?.smoothScrollTo(it.left, 0) }
                }
                HapticUtils.segmentTick(notesView)
                displayRelease(targetRelease)
            } else {
                lifecycleScope.launch {
                    try {
                        val notes = UpdateChecker.fetchReleaseNotesForTag(targetTag)
                        if (notes != null && isAdded && !isDetached) {
                            val singleRelease = ReleaseItem(tag = targetTag, date = "", body = notes)
                            HapticUtils.segmentTick(notesView)
                            displayRelease(singleRelease)
                        } else if (isAdded && !isDetached) {
                            CustomTabsHelper.launchUrlOrCopy(
                                requireContext(),
                                "https://github.com/frdminc/ShizukuTendCF/releases/tag/$targetTag",
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load release for tag $targetTag")
                    }
                }
            }
        }

        // Initial release display
        if (combinedNewRelease != null) {
            displayRelease(combinedNewRelease, isCombined = true)
        } else if (releases.isNotEmpty()) {
            displayRelease(releases.first())
        } else {
            versionText.text = tagName
            notesView.setText(R.string.changelog_fallback_message)
        }
        notesView.movementMethod = LinkMovementMethod.getInstance()

        // "View on GitHub" links to the currently selected release's page
        btnGithub.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/frdminc/ShizukuTendCF/releases/tag/$currentSelectedTag"),
                    ),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to open release page for $currentSelectedTag")
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_close).setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        val dlg = dialog as? BottomSheetDialog ?: return
        val sheet = dlg.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val screenHeight = resources.displayMetrics.heightPixels
        sheet.layoutParams = sheet.layoutParams.apply { height = (screenHeight * 0.82).toInt() }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    private fun parseReleases(json: String?): List<ReleaseItem> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length())
                .map { i ->
                    val obj = arr.getJSONObject(i)
                    ReleaseItem(
                        tag = obj.optString("tag", ""),
                        date = obj.optString("date", ""),
                        body = obj.optString("body", ""),
                        isNew = obj.optBoolean("is_new", false),
                    )
                }.filter { it.tag.isNotBlank() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse releases JSON")
            emptyList()
        }
    }
}
