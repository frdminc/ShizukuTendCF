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
            every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
            AdbAuthWait.clearStartNotification {}
            AdbAuthWait.tryBegin() shouldBe true
        }

        afterTest {
            AdbAuthWait.end()
        }

        test("stands down before posting progress and clears a notification no start has claimed") {
            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.notify(any<Int>(), any<Notification>()) }
            verify(exactly = 1) { nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID) }
        }

        test("leaves the holder's authorisation prompt in place") {
            AdbAuthWait.postAuthPrompt {}

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.notify(any<Int>(), any<Notification>()) }
            verify(exactly = 0) { nm.cancel(any<Int>()) }
        }

        test("leaves a notification another start posted") {
            AdbAuthWait.postStartNotification(Any()) {}

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.cancel(any<Int>()) }
        }

        test("progress cannot cover the prompt while its wait is held, and can once it ends") {
            var posts = 0
            AdbAuthWait.postAuthPrompt {}

            AdbAuthWait.postStartNotification(Any()) { posts++ }
            posts shouldBe 0

            AdbAuthWait.end()
            AdbAuthWait.postStartNotification(Any()) { posts++ }
            posts shouldBe 1
        }

        test("a stood-down start removes its own progress but not what replaced it") {
            val self = Any()
            var cancels = 0
            AdbAuthWait.postStartNotification(self) {}
            AdbAuthWait.withdrawStartNotification(self) { cancels++ }
            cancels shouldBe 1

            AdbAuthWait.postStartNotification(self) {}
            AdbAuthWait.postAuthPrompt {}
            AdbAuthWait.withdrawStartNotification(self) { cancels++ }
            cancels shouldBe 1
        }
    })
