package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartRootBinding
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartStockShizukuViewHolder(
    private val binding: HomeStartRootBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
) : BaseViewHolder<Boolean>(containerBinding.root) {
    companion object {
        fun creator(scope: CoroutineScope): Creator<Boolean> =
            Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartRootBinding.inflate(inflater, outer.cardContent, true)
                StartStockShizukuViewHolder(inner, outer, scope)
            }
    }

    private inline val start get() = binding.button1
    private inline val restart get() = binding.button2

    init {
        containerBinding.root.applySpringTouch()
        val listener = View.OnClickListener { v: View -> onStartClicked(v) }
        start.setOnClickListener(listener)
        restart.visibility = View.GONE
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun onStartClicked(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        start.isEnabled = false
        scope.launch(Dispatchers.IO) {
            // Checking for root can wait on the su prompt, so it stays off the main thread. A
            // live Shizuku binder is used first: it isn't root, so it mustn't go through su.
            val hasShizuku = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
            val hasRoot =
                !hasShizuku &&
                    runCatching {
                        com.topjohnwu.superuser.Shell
                            .isAppGrantedRoot() == true ||
                            com.topjohnwu.superuser.Shell
                                .getShell()
                                .isRoot
                    }.getOrDefault(false)
            val ran =
                if (!hasShizuku && !hasRoot) {
                    null
                } else {
                    val starterCmd = af.shizuku.manager.starter.Starter.internalCommand
                    val cmd = "am force-stop moe.shizuku.privileged.api && am force-stop af.shizuku.plus.api && nohup sh -c 'sleep 1 && $starterCmd' >/dev/null 2>&1 &"
                    runCatching {
                        if (hasShizuku) {
                            @Suppress("DEPRECATION")
                            rikka.shizuku.Shizuku
                                .newProcess(arrayOf("sh", "-c", cmd), null, null)
                                ?.waitFor() == 0
                        } else {
                            com.topjohnwu.superuser.Shell
                                .cmd(cmd)
                                .exec()
                                .isSuccess
                        }
                    }.onFailure { timber.log.Timber.w(it, "Restarting over the stock Shizuku server failed") }
                        .getOrDefault(false)
                }
            withContext(Dispatchers.Main) {
                start.isEnabled = true
                when (ran) {
                    true ->
                        android.widget.Toast
                            .makeText(activity, if (hasShizuku) R.string.stock_shizuku_restarting else R.string.stock_shizuku_restarting_via_root, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    // No privileged shell, or it failed: only a reboot clears the stock server.
                    else ->
                        com.google.android.material.dialog
                            .MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.stock_shizuku_conflict_dialog_title)
                            .setMessage(R.string.stock_shizuku_conflict_dialog_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                }
            }
        }
    }

    override fun onBind() {
        start.isEnabled = true
        start.setText(R.string.stock_shizuku_conflict_fix_button)
        binding.title.setText(R.string.stock_shizuku_conflict_title)
        binding.text1.setText(R.string.stock_shizuku_conflict_description)
        binding.icon.setImageResource(R.drawable.ic_warning_24)

        // Proper Material error-container two-tone instead of a hardcoded Color.RED tint on the
        // default secondary/tertiary-container pill, so this warning reads correctly in both
        // light/dark themes and shares the same shape as every other Two-Tone icon in the app.
        val context = binding.icon.context
        val errorContainer = context.themeColor(com.google.android.material.R.attr.colorErrorContainer)
        val onErrorContainer = context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        af.shizuku.manager.utils.IconStyleHelper
            .applyToStatusCardIcon(binding.icon, pillColor = errorContainer, tintColor = onErrorContainer)
    }
}
