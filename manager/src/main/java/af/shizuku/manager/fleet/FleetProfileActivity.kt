package af.shizuku.manager.fleet

import af.shizuku.manager.BuildConfig
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class FleetProfileActivity : Activity() {
    companion object {
        const val ACTION_APPLY_FLEET_PROFILE = "${BuildConfig.APPLICATION_ID}.APPLY_FLEET_PROFILE"
        const val EXTRA_PROFILE_PATH = "profile_path"
        const val EXTRA_SILENT = "silent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = handleIntent(intent)
        // A missing data URI counts as the path channel: that is the extra the caller left out.
        val source = if (intent.data != null) FleetApplyReport.SOURCE_URI else FleetApplyReport.SOURCE_PATH
        FleetApplyReport.record(this, result, source)
        if (!intent.getBooleanExtra(EXTRA_SILENT, false)) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
        setResult(if (result.success) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun handleIntent(intent: Intent): FleetProfileApplier.Result =
        try {
            val data = intent.data
            val path = intent.getStringExtra(EXTRA_PROFILE_PATH)
            when {
                data != null -> FleetProfileApplier.applyFromUri(this, data)
                path != null -> FleetProfileApplier.applyFromPath(this, path)
                else ->
                    FleetProfileApplier.Result(
                        false,
                        0,
                        0,
                        listOf("Missing profile_path or data URI"),
                        "Missing profile_path or data URI",
                    )
            }
        } catch (e: Exception) {
            FleetProfileApplier.Result(
                false,
                0,
                0,
                listOf(e.message ?: "Unknown error"),
                "Failed to apply profile: ${e.message}",
            )
        }
}
