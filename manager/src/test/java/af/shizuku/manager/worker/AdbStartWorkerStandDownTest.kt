package af.shizuku.manager.worker

import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class AdbStartWorkerStandDownTest :
    FunSpec({

        lateinit var nm: NotificationManager
        lateinit var context: Context
        val workerParams: WorkerParameters = mockk(relaxed = true)

        beforeTest {
            nm = mockk(relaxed = true)
            context = mockk(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
            AdbAuthWait.cancelAttempts({}, {})
            AdbAuthWait.tryBegin() shouldBe true
        }

        afterTest {
            AdbAuthWait.end()
        }

        test("stands down before posting progress and clears a leftover no start in this process posted") {
            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.notify(any<Int>(), any<Notification>()) }
            verify(exactly = 1) { nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID) }
        }

        test("leaves the holder's authorisation prompt in place") {
            // No preferences on the JVM, so nothing can be numbered here; the holder is some
            // attempt scheduled elsewhere.
            AdbAuthWait.postAuthPrompt(1L, {}, {})

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.notify(any<Int>(), any<Notification>()) }
            verify(exactly = 0) { nm.cancel(any<Int>()) }
        }

        test("leaves a notification another start posted") {
            var removed = false
            AdbAuthWait.postStartNotice({}, { removed = true }) shouldBe true

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            removed shouldBe false
            verify(exactly = 0) { nm.cancel(any<Int>()) }
        }

        test("a stood-down worker is not left counted as start work in flight") {
            val before = AdbAuthWait.starts.state.value.running

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            AdbAuthWait.starts.state.value.running shouldBe before
        }
    })
