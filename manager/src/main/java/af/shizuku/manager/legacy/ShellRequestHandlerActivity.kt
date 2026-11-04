package af.shizuku.manager.legacy

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.shell.ShellBinderRequestHandler
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import rikka.material.app.MaterialActivity

class ShellRequestHandlerActivity : MaterialActivity() {
    companion object {
        private const val CHANNEL_ID = "auth_errors"
        private const val CHANNEL_NAME = "Authentication Errors"
        private const val NOTIFICATION_ID = 1450
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawToken =
            intent.getStringExtra("auth")
                ?: intent.getStringExtra("token")
                ?: intent.getStringExtra("auth_token")

        val expectedToken = ShizukuSettings.getAuthToken()

        val tokenCandidate =
            rawToken?.trim()?.removeSurrounding("\"")?.let {
                if (it.startsWith("auth:")) it.substring(5).trim() else it
            }

        val decryptedToken =
            if (!tokenCandidate.isNullOrEmpty()) {
                af.shizuku.manager.utils.IntentCrypto
                    .decrypt(tokenCandidate)
            } else {
                null
            }

        val isValid =
            when {
                tokenCandidate.isNullOrEmpty() -> false
                decryptedToken != null && java.security.MessageDigest.isEqual(decryptedToken.toByteArray(), expectedToken.toByteArray()) -> true
                java.security.MessageDigest.isEqual(tokenCandidate.toByteArray(), expectedToken.toByteArray()) -> true
                else -> false
            }

        if (tokenCandidate.isNullOrEmpty()) {
            notify(
                R.string.notification_auth_missing_title,
                R.string.notification_auth_missing_message,
            )
        } else if (!isValid) {
            notify(
                R.string.notification_auth_invalid_title,
                R.string.notification_auth_invalid_message,
            )
        } else {
            ShellBinderRequestHandler.handleRequest(this, intent, true)
        }
        finish()
    }

    private fun notify(
        title: Int,
        message: Int,
    ) {
        if (!ShizukuSettings.isNotificationSystemEnabled()) return
        val titleStr = getString(title)
        val messageStr = getString(message)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                )
            nm.createNotificationChannel(channel)
        }

        val launchIntent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        val launchPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(titleStr)
                .setContentText(messageStr)
                .setContentIntent(launchPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
