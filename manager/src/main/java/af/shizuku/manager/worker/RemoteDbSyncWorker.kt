package af.shizuku.manager.worker

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class RemoteDbSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val WORK_NAME = "remote_app_db_sync"
        private const val DB_URL =
<<<<<<< HEAD
            "https://raw.githubusercontent.com/frdminc/ShizukuTendCF/master/app-context-db.json"
=======
            "https://raw.githubusercontent.com/thejaustin/ShizukuPlus/master/database/src/main/assets/app_context_db.json"
>>>>>>> upstream/master
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000

        // Only re-fetch if the cached data is older than 20 hours
        private const val MIN_REFRESH_INTERVAL_MS = 20 * 60 * 60 * 1_000L

        /** Tests only: replaces the network fetch. Null uses the real one. */
        @androidx.annotation.VisibleForTesting
        internal var fetchForTesting: ((String) -> String?)? = null

        sealed interface SyncResult {
            /** A newer revision was applied and [apps] apps' info changed. */
            data class Updated(
                val apps: Int,
            ) : SyncResult

            /** Nothing newer than what's installed. */
            data object UpToDate : SyncResult

            data class Failed(
                val retry: Boolean,
            ) : SyncResult
        }

        /** Fetches and applies the remote app database. Shared by the daily job and Settings' Check now. */
        suspend fun sync(): SyncResult =
            withContext(Dispatchers.IO) {
                try {
                    when (val f = fetchForTesting.let { seam -> if (seam != null) seam(DB_URL)?.let(Fetched::Body) ?: Fetched.NotModified else fetch(DB_URL) }) {
                        Fetched.NotModified -> {
                            ShizukuSettings.setLastDbUpdate(System.currentTimeMillis())
                            SyncResult.UpToDate
                        }
                        is Fetched.Body -> {
                            val applied =
                                try {
                                    AppContextManager.updateDatabase(f.json)
                                } catch (e: Exception) {
                                    Timber.w(e, "RemoteDbSync: downloaded database is invalid")
                                    return@withContext SyncResult.Failed(retry = false)
                                }
                            // Only remember the version once it parsed, or a bad download would 304 forever.
                            f.etag?.let { ShizukuSettings.setRemoteDbEtag(it) }
                            f.lastModified?.let { ShizukuSettings.setRemoteDbLastModified(it) }
                            ShizukuSettings.setLastDbUpdate(System.currentTimeMillis())
                            if (applied == null || applied == 0) SyncResult.UpToDate else SyncResult.Updated(applied)
                        }
                        is Fetched.Error -> SyncResult.Failed(retry = f.code >= 500)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "RemoteDbSync: network fetch failed")
                    SyncResult.Failed(retry = true)
                }
            }

        private sealed interface Fetched {
            data class Body(
                val json: String,
                val etag: String? = null,
                val lastModified: String? = null,
            ) : Fetched

            data object NotModified : Fetched

            data class Error(
                val code: Int,
            ) : Fetched
        }

        private fun fetch(urlString: String): Fetched {
            val connection =
                (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/json")
                    // Conditional only while a download is actually in use; otherwise always take the body.
                    if (ShizukuSettings.getRemoteDbJson() != null) {
                        ShizukuSettings.getRemoteDbEtag()?.let { setRequestProperty("If-None-Match", it) }
                        ShizukuSettings.getRemoteDbLastModified()?.let { setRequestProperty("If-Modified-Since", it) }
                    }
                }
            return try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        if (body.isBlank()) {
                            Fetched.Error(code)
                        } else {
                            Fetched.Body(body, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                        }
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> Fetched.NotModified
                    else -> {
                        Timber.w("RemoteDbSync: HTTP %d from %s", code, urlString)
                        Fetched.Error(code)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        fun schedule(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<RemoteDbSyncWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(5, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    // The network fetch, replaceable by tests: java.net.URL cannot be constructor-mocked on current JDKs.
    internal var fetchDb: (String) -> String? = ::fetch

    override suspend fun doWork(): Result {
        val lastUpdate = ShizukuSettings.getLastDbUpdate()
        if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate < MIN_REFRESH_INTERVAL_MS) {
            Timber.d("RemoteDbSync: skipping — checked %dh ago", (System.currentTimeMillis() - lastUpdate) / 3600_000)
            return Result.success()
        }
<<<<<<< HEAD

        return try {
            val json = fetchDb(DB_URL)
            if (json != null) {
                AppContextManager.updateDatabase(json)
                Timber.d("RemoteDbSync: app context database updated successfully")
            } else {
                Timber.w("RemoteDbSync: server returned empty or error response")
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "RemoteDbSync: network fetch failed, will retry next cycle")
            Result.retry()
        }
    }

    private fun fetch(urlString: String): String? {
        val connection =
            (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/json")

                ShizukuSettings.getRemoteDbEtag()?.let {
                    setRequestProperty("If-None-Match", it)
                }
                ShizukuSettings.getRemoteDbLastModified()?.let {
                    setRequestProperty("If-Modified-Since", it)
                }
            }
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val etag = connection.getHeaderField("ETag")
                    val lastModified = connection.getHeaderField("Last-Modified")

                    ShizukuSettings.setRemoteDbEtag(etag)
                    ShizukuSettings.setRemoteDbLastModified(lastModified)

                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .takeIf { it.isNotBlank() }
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Timber.d("RemoteDbSync: 304 Not Modified — using local cache")
                    // If not modified, the cache is implicitly fresh, update the timestamp
                    ShizukuSettings.setLastDbUpdate(System.currentTimeMillis())
                    null
                }
                else -> {
                    Timber.w("RemoteDbSync: HTTP ${connection.responseCode} from $urlString")
                    null
                }
            }
        } finally {
            connection.disconnect()
=======
        return when (val r = sync()) {
            is SyncResult.Failed -> if (r.retry) Result.retry() else Result.success()
            else -> Result.success()
>>>>>>> upstream/master
        }
    }
}
