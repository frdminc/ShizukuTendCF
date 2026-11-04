package af.shizuku.manager.worker

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify

class RemoteDbSyncWorkerTest :
    FunSpec({

        val context: Context = mockk(relaxed = true)
        val workerParams: WorkerParameters = mockk(relaxed = true)

        beforeTest {
            mockkStatic(ShizukuSettings::class)
            mockkObject(AppContextManager)
            every { ShizukuSettings.setLastDbUpdate(any()) } just runs
            every { ShizukuSettings.setRemoteDbEtag(any()) } just runs
            every { ShizukuSettings.setRemoteDbLastModified(any()) } just runs
        }

        afterTest {
            RemoteDbSyncWorker.fetchForTesting = null
            unmockkAll()
        }

        test("doWork returns success when cache is fresh") {
            // Return a recent timestamp to skip fetch
            every { ShizukuSettings.getLastDbUpdate() } returns System.currentTimeMillis() - 1000L

            val worker = RemoteDbSyncWorker(context, workerParams)
            val result = worker.doWork()

            result shouldBe Result.success()
        }

        test("doWork returns retry when fetch throws exception") {
            every { ShizukuSettings.getLastDbUpdate() } returns 0L

            RemoteDbSyncWorker.fetchForTesting = { throw RuntimeException("Simulated network error") }

            val worker = RemoteDbSyncWorker(context, workerParams)
            val result = worker.doWork()

            result shouldBe Result.retry()
        }

        test("doWork updates the database when the fetch returns data") {
            every { ShizukuSettings.getLastDbUpdate() } returns 0L
            every { AppContextManager.updateDatabase(any()) } returns 3
            RemoteDbSyncWorker.fetchForTesting = { "{}" }

            val worker = RemoteDbSyncWorker(context, workerParams)
            val result = worker.doWork()

            result shouldBe Result.success()
            verify(exactly = 1) { AppContextManager.updateDatabase("{}") }
        }

        test("sync reports a parse failure without retrying or saving the version") {
            every { AppContextManager.updateDatabase(any()) } throws IllegalArgumentException("bad json")
            RemoteDbSyncWorker.fetchForTesting = { "nope" }

            RemoteDbSyncWorker.sync() shouldBe RemoteDbSyncWorker.Companion.SyncResult.Failed(retry = false)
            verify(exactly = 0) { ShizukuSettings.setLastDbUpdate(any()) }
        }

        test("sync says up to date when the download isn't newer") {
            every { AppContextManager.updateDatabase(any()) } returns null
            RemoteDbSyncWorker.fetchForTesting = { "{}" }

            RemoteDbSyncWorker.sync() shouldBe RemoteDbSyncWorker.Companion.SyncResult.UpToDate
        }
    })
