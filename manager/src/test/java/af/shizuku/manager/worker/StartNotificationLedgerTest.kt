package af.shizuku.manager.worker

import af.shizuku.manager.adb.StartNotificationLedger
import af.shizuku.manager.adb.StartNotificationLedger.Companion.NO_ATTEMPT
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The slot rules for the shared ADB-start notification, driven the way the worker, AdbStarter,
 * the enqueue listener and the notification actions drive them. "Visible" is what a user would see.
 */
class StartNotificationLedgerTest :
    FunSpec({

        var visible: String? = null
        lateinit var ledger: StartNotificationLedger

        // Durable storage shared by every "process" in a test, as the preference is on a device.
        var stored = NO_ATTEMPT

        fun processLedger() =
            StartNotificationLedger({ stored }, {
                stored = it
                true
            })

        fun show(text: String): () -> Unit = { visible = text }
        val hide: () -> Unit = { visible = null }

        fun StartNotificationLedger.progress(
            attempt: Long,
            text: String,
        ) = post(attempt, show(text), hide)

        // WorkManager confirmed the enqueue; every enqueue brings its own pending status.
        fun StartNotificationLedger.confirmed(
            attempt: Long,
            text: String,
        ) = enqueued(attempt, show(text), hide)

        fun StartNotificationLedger.retry(
            attempt: Long,
            text: String,
        ) = retrying(attempt, show(text), hide)

        fun StartNotificationLedger.prompt(
            attempt: Long,
            text: String = "prompt",
        ) = postPrompt(attempt, show(text), hide)

        fun StartNotificationLedger.unanswered(attempt: Long) = finish(attempt, show("not answered"), hide)

        // The user swiped the notification away.
        fun StartNotificationLedger.swipe() {
            visible = null
            restore()
        }

        // A start scheduled and its worker now running.
        fun StartNotificationLedger.started(): Long = adopt(schedule {})

        class Step(
            val name: String,
            val action: StartNotificationLedger.() -> Unit,
        )

        fun step(
            name: String,
            action: StartNotificationLedger.() -> Unit,
        ) = Step(name, action)

        // Every order in which [threads] can interleave, each thread's own steps staying in order.
        fun interleavings(threads: List<List<Step>>): List<List<Step>> =
            if (threads.all { it.isEmpty() }) {
                listOf(emptyList())
            } else {
                threads.indices.filter { threads[it].isNotEmpty() }.flatMap { i ->
                    val rest = threads.mapIndexed { j, t -> if (j == i) t.drop(1) else t }
                    interleavings(rest).map { listOf(threads[i].first()) + it }
                }
            }

        // On a fresh process for each order: [setup], then that interleaving of [threads], then
        // [then] step by step. Returns, per order, what was visible after the interleaving and
        // after each step of [then].
        fun everyOrder(
            setup: StartNotificationLedger.() -> Unit,
            vararg threads: List<Step>,
            then: List<Step> = emptyList(),
        ): Map<String, List<String?>> =
            interleavings(threads.toList()).associate { order ->
                visible = null
                stored = NO_ATTEMPT
                ledger = processLedger()
                ledger.setup()
                order.forEach { it.action(ledger) }
                val seen = mutableListOf(visible)
                then.forEach {
                    it.action(ledger)
                    seen += visible
                }
                order.joinToString(" -> ") { it.name } to seen.toList()
            }

        // Every order must leave the same thing visible; a failure names the orders that did not.
        fun Map<String, List<String?>>.allShow(vararg expected: String?) {
            filterValues { it != expected.toList() } shouldBe emptyMap<String, List<String?>>()
        }

        beforeTest {
            visible = null
            stored = NO_ATTEMPT
            ledger = processLedger()
        }

        test("nothing is shown or owned for an attempt until it is confirmed or its worker runs") {
            val a = ledger.schedule {}
            ledger.progress(a, "a") shouldBe false
            visible shouldBe null

            ledger.adopt(a) shouldBe a
            ledger.progress(a, "a") shouldBe true
            visible shouldBe "a"
        }

        test("a prompt shows over progress; when its wait ends the progress beneath it shows") {
            val a = ledger.started()
            ledger.progress(a, "starting")
            ledger.prompt(a)
            visible shouldBe "prompt"

            ledger.progress(a, "still starting") shouldBe true
            visible shouldBe "prompt"

            ledger.waitEnded()
            visible shouldBe "still starting"
        }

        test("the end of a run removes its progress; a retried run of the owner owns again") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.withdraw(a, hide)
            visible shouldBe null
            ledger.progress(a, "a between runs") shouldBe false
            visible shouldBe null

            ledger.adopt(a)
            ledger.progress(a, "a again") shouldBe true
            visible shouldBe "a again"
        }

        test("a prompt goes when its wait ends, with nothing beneath it") {
            val a = ledger.started()
            ledger.prompt(a)
            ledger.waitEnded()
            visible shouldBe null
            ledger.withdraw(a, hide)
            visible shouldBe null
        }

        test("a stood-down attempt leaves a newer attempt's progress and a prompt alone") {
            val a = ledger.started()
            ledger.progress(a, "a")
            val b = ledger.started()
            ledger.progress(b, "b")
            ledger.withdraw(a, hide)
            visible shouldBe "b"

            ledger.prompt(b)
            val c = ledger.started()
            ledger.withdraw(c, hide)
            visible shouldBe "prompt"
        }

        test("a superseded attempt cannot post") {
            val a = ledger.started()
            ledger.progress(a, "a")
            val b = ledger.started()
            visible shouldBe null

            ledger.progress(a, "a retry") shouldBe false
            visible shouldBe null

            ledger.progress(b, "b")
            ledger.progress(a, "a retry") shouldBe false
            visible shouldBe "b"
        }

        test("success clears its own and older content but not a newer attempt's or a prompt") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.clear(a, hide)
            visible shouldBe null

            ledger.notice(show("permission"), hide)
            val b = ledger.started()
            ledger.clear(b, hide)
            visible shouldBe null

            val c = ledger.started()
            ledger.progress(c, "c")
            val d = ledger.started()
            ledger.progress(d, "d")
            ledger.clear(c, hide)
            visible shouldBe "d"

            ledger.prompt(d)
            ledger.clear(c, hide)
            visible shouldBe "prompt"
        }

        test("success with nothing posted in this process still removes a leftover from a dead one") {
            stored = 7L
            visible = "left by a dead process"
            ledger.clear(ledger.adopt(7L), hide)
            visible shouldBe null
        }

        test("an unanswered prompt ends the attempt with a notice that its run's end leaves, and a swipe dismisses") {
            val a = ledger.started()
            ledger.progress(a, "starting")
            ledger.unanswered(a) shouldBe true
            visible shouldBe "not answered"

            ledger.progress(a, "retry") shouldBe false
            ledger.withdraw(a, hide)
            visible shouldBe "not answered"

            ledger.swipe()
            visible shouldBe null
        }

        test("a swipe puts back progress, a prompt and what lies beneath, never what has ended") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.swipe()
            visible shouldBe "a"

            ledger.prompt(a)
            ledger.swipe()
            visible shouldBe "prompt"

            ledger.waitEnded()
            visible shouldBe "a"
            ledger.swipe()
            visible shouldBe "a"

            ledger.clear(a, hide)
            ledger.swipe()
            visible shouldBe null
        }

        test("user cancel ends the attempt, so the cancelled worker's report cannot bring it back") {
            val a = ledger.started()
            ledger.progress(a, "starting")
            var workCancelled = false
            ledger.cancel(hide) { workCancelled = true }
            visible shouldBe null
            workCancelled shouldBe true

            ledger.progress(a, "will retry") shouldBe false
            ledger.adopt(a)
            ledger.progress(a, "will retry") shouldBe false
            ledger.retry(a, "will retry") shouldBe false
            ledger.unanswered(a) shouldBe false
            visible shouldBe null
        }

        test("user cancel also ends attempts whose workers have not run yet, here or in a dead process") {
            val queued = ledger.schedule {}
            ledger.cancel(hide) {}
            ledger.adopt(queued)
            ledger.progress(queued, "queued") shouldBe false

            stored = 5L
            val restarted = processLedger()
            restarted.cancel(hide) {}
            restarted.adopt(5L)
            restarted.progress(5L, "five") shouldBe false

            val next = restarted.started()
            next shouldBe 6L
            restarted.progress(next, "six") shouldBe true
        }

        test("a cancel racing a schedule comes wholly after it, so it cancels the work just submitted") {
            val events = mutableListOf<String>()
            val cancelling = CountDownLatch(1)
            lateinit var canceller: Thread

            val n =
                ledger.schedule {
                    canceller =
                        thread {
                            cancelling.countDown()
                            ledger.cancel(hide) { synchronized(events) { events += "cancel" } }
                        }
                    cancelling.await(5, TimeUnit.SECONDS) shouldBe true
                    // Give the other thread every chance to cancel mid-schedule if it could.
                    Thread.sleep(100)
                    synchronized(events) { events += "submit" }
                }
            canceller.join(5_000)

            events shouldBe listOf("submit", "cancel")
            ledger.adopt(n)
            ledger.progress(n, "n") shouldBe false
        }

        test("a worker from a dead process adopts its recorded attempt until a newer start runs") {
            stored = 5L
            ledger.adopt(5L) shouldBe 5L
            ledger.progress(5L, "five") shouldBe true
            ledger.started() shouldBe 6L
            ledger.progress(5L, "five") shouldBe false
        }

        test("a number that was never recorded is not adopted") {
            stored = 3L
            ledger.adopt(5L)
            ledger.progress(5L, "five") shouldBe false
            visible shouldBe null
        }

        test("a worker without a number takes none, so its REPLACE cancellation cannot retire the replacement") {
            val fresh = ledger.started()
            ledger.progress(fresh, "fresh")

            val legacy = ledger.adopt(NO_ATTEMPT)
            legacy shouldBe NO_ATTEMPT
            ledger.progress(legacy, "legacy") shouldBe false
            ledger.withdraw(legacy, hide)
            ledger.clear(legacy, hide)
            visible shouldBe "fresh"

            ledger.progress(fresh, "fresh retry") shouldBe true
            ledger.unanswered(fresh) shouldBe true
            visible shouldBe "not answered"
        }

        test("a worker without a number still shows its prompt, which goes when its wait ends") {
            val legacy = ledger.adopt(NO_ATTEMPT)
            ledger.prompt(legacy)
            visible shouldBe "prompt"

            ledger.waitEnded()
            visible shouldBe null
        }

        test("a stood-down worker without a number leaves a notice alone") {
            ledger.notice(show("permission"), hide)
            ledger.withdraw(ledger.adopt(NO_ATTEMPT), hide)
            visible shouldBe "permission"
        }

        test("after a restart a fresh start outranks the dead process's persisted worker, which adopts too late") {
            val dead = processLedger()
            repeat(4) { dead.schedule {} }
            val persisted = dead.schedule {}
            persisted shouldBe 5L

            val restarted = processLedger()
            val fresh = restarted.started()
            fresh shouldBe 6L
            restarted.progress(fresh, "fresh") shouldBe true

            restarted.adopt(persisted) shouldBe persisted
            restarted.progress(persisted, "stale") shouldBe false
            visible shouldBe "fresh"

            // Its cancellation by REPLACE neither retires the replacement nor takes its notification.
            restarted.withdraw(persisted, hide)
            visible shouldBe "fresh"
            restarted.unanswered(fresh) shouldBe true
            visible shouldBe "not answered"
        }

        test("after a restart a persisted worker that adopts first is superseded once the next start runs") {
            val persisted = processLedger().schedule {}

            val restarted = processLedger()
            restarted.adopt(persisted) shouldBe persisted
            restarted.progress(persisted, "stale") shouldBe true

            val fresh = restarted.started()
            (fresh > persisted) shouldBe true
            visible shouldBe null
            restarted.progress(persisted, "stale") shouldBe false
            restarted.progress(fresh, "fresh") shouldBe true
            restarted.clear(fresh, hide)
            visible shouldBe null
        }

        test("numbers are never reused across restarts, so equal numbers cannot collide") {
            val issued = mutableSetOf<Long>()
            repeat(3) {
                val process = processLedger()
                repeat(3) { issued += process.schedule {} }
            }
            issued.size shouldBe 9
        }

        test("a number that cannot be recorded is not issued, and nothing is submitted for it") {
            var writable = true
            val l =
                StartNotificationLedger({ stored }, {
                    if (writable) stored = it
                    writable
                })
            val a = l.started()
            l.progress(a, "a")

            writable = false
            var submitted = false
            l.schedule { submitted = true } shouldBe NO_ATTEMPT
            submitted shouldBe false
            visible shouldBe "a"
            l.progress(a, "a retry") shouldBe true

            // A dead process could only have scheduled numbers it recorded, so after a restart
            // the next start still outranks every persisted worker.
            writable = true
            val restarted = processLedger()
            (restarted.schedule {} > a) shouldBe true
        }

        test("storage that cannot be read issues no number") {
            var submitted = false
            StartNotificationLedger({ error("unreadable") }, { true })
                .schedule { submitted = true } shouldBe NO_ATTEMPT
            submitted shouldBe false
        }

        test("an enqueue that throws spends a number and nothing else: the running owner and its progress stay") {
            val a = ledger.started()
            ledger.progress(a, "a")
            var failed = NO_ATTEMPT
            runCatching {
                ledger.schedule {
                    failed = it
                    error("enqueue failed")
                }
            }.isFailure shouldBe true
            visible shouldBe "a"

            ledger.progress(a, "a again") shouldBe true
            ledger.progress(failed, "b") shouldBe false
            visible shouldBe "a again"
            (ledger.schedule {} > failed) shouldBe true
        }

        test("replacements that are never confirmed leave the running owner and its progress alone") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.schedule {}
            ledger.schedule {}
            visible shouldBe "a"

            // a is still the owner inside its binder wait, so its progress is still current.
            ledger.swipe()
            visible shouldBe "a"
            ledger.unanswered(a) shouldBe true
            visible shouldBe "not answered"
        }

        test("nothing shows an attempt's progress again once its run has ended") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.schedule {}
            ledger.clear(a, hide)
            visible shouldBe null

            ledger.swipe()
            ledger.progress(a, "a") shouldBe false
            visible shouldBe null
        }

        test("a new running attempt removes the superseded attempt's progress but not a prompt") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.started()
            visible shouldBe null

            val c = ledger.started()
            ledger.prompt(c)
            ledger.started()
            visible shouldBe "prompt"
        }

        test("progress steps aside for the foreground notification and only progress does") {
            val a = ledger.started()
            ledger.progress(a, "a")
            ledger.hideProgress(a)
            visible shouldBe null
            ledger.progress(a, "a retry") shouldBe true

            ledger.unanswered(a)
            ledger.hideProgress(a)
            visible shouldBe "not answered"

            val b = ledger.started()
            ledger.prompt(b)
            ledger.hideProgress(b)
            visible shouldBe "prompt"
        }

        test("the pending status appears only once the enqueue is confirmed; an enqueue that fails sets nothing") {
            val a = ledger.schedule {}
            visible shouldBe null
            ledger.confirmed(a, "wifi required") shouldBe true
            visible shouldBe "wifi required"

            // A replacement whose enqueue fails is never confirmed: a's request and status stay.
            ledger.schedule {}
            visible shouldBe "wifi required"
            ledger.swipe()
            visible shouldBe "wifi required"
        }

        test("the worker replaces its pending status with progress; a run that will retry leaves one again") {
            val a = ledger.schedule {}
            ledger.confirmed(a, "wifi required")
            ledger.adopt(a)
            visible shouldBe "wifi required"
            ledger.progress(a, "running") shouldBe true
            visible shouldBe "running"

            ledger.retry(a, "will retry") shouldBe true
            visible shouldBe "will retry"
            ledger.swipe()
            visible shouldBe "will retry"

            ledger.adopt(a)
            ledger.progress(a, "running again") shouldBe true
            visible shouldBe "running again"
        }

        test("terminal ends remove the pending status: stand-down, success, unanswered prompt") {
            val a = ledger.schedule {}
            ledger.confirmed(a, "pending")
            ledger.adopt(a)
            ledger.withdraw(a, hide)
            visible shouldBe null

            val b = ledger.schedule {}
            ledger.confirmed(b, "pending")
            ledger.adopt(b)
            ledger.progress(b, "running")
            ledger.retry(b, "will retry")
            ledger.adopt(b)
            ledger.clear(b, hide)
            visible shouldBe null

            val c = ledger.schedule {}
            ledger.confirmed(c, "pending")
            ledger.adopt(c)
            ledger.unanswered(c) shouldBe true
            visible shouldBe "not answered"
            ledger.swipe()
            visible shouldBe null
        }

        test("a late confirmation is refused once the attempt's worker has run, and changes nothing") {
            val a = ledger.schedule {}
            ledger.adopt(a)
            ledger.progress(a, "running")
            ledger.confirmed(a, "pending") shouldBe false
            visible shouldBe "running"
            ledger.progress(a, "still running") shouldBe true

            ledger.retry(a, "will retry")
            ledger.confirmed(a, "pending") shouldBe false
            visible shouldBe "will retry"

            ledger.adopt(a)
            ledger.clear(a, hide)
            ledger.confirmed(a, "pending") shouldBe false
            visible shouldBe null
        }

        test("a late confirmation is refused once a newer enqueue was confirmed or a cancel ended it") {
            val a = ledger.schedule {}
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending") shouldBe true
            ledger.confirmed(a, "a pending") shouldBe false
            visible shouldBe "b pending"

            val c = ledger.schedule {}
            ledger.cancel(hide) {}
            ledger.confirmed(c, "c pending") shouldBe false
            visible shouldBe null
        }

        test("cancel removes the pending status and cancels the work; nothing of the attempt shows again") {
            val a = ledger.schedule {}
            ledger.confirmed(a, "pending")
            var workCancelled = false
            ledger.cancel(hide) { workCancelled = true }
            visible shouldBe null
            workCancelled shouldBe true

            ledger.adopt(a)
            ledger.progress(a, "running") shouldBe false
            ledger.retry(a, "will retry") shouldBe false
            ledger.swipe()
            visible shouldBe null

            // Cancelled while in backoff: the run that ends after the cancel cannot leave a status.
            val b = ledger.started()
            ledger.retry(b, "will retry")
            ledger.cancel(hide) {}
            visible shouldBe null
            ledger.adopt(b)
            ledger.retry(b, "will retry") shouldBe false
            visible shouldBe null
        }

        test("a confirmed replacement's own status shows once the replaced run's progress goes") {
            val a = ledger.started()
            ledger.progress(a, "a running")
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending") shouldBe true
            visible shouldBe "a running"

            // REPLACE stopped a: it can no longer post or leave a pending status of its own.
            ledger.progress(a, "a again") shouldBe false
            ledger.retry(a, "a will retry") shouldBe false
            visible shouldBe "b pending"
            ledger.withdraw(a, hide)
            visible shouldBe "b pending"
            ledger.adopt(a)
            ledger.progress(a, "a") shouldBe false

            ledger.adopt(b)
            ledger.progress(b, "b running") shouldBe true
            visible shouldBe "b running"
        }

        test("a confirmed replacement takes over from a pending status in backoff, and its stand-down removes it") {
            val a = ledger.started()
            ledger.retry(a, "will retry")
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending") shouldBe true
            visible shouldBe "b pending"
            ledger.swipe()
            visible shouldBe "b pending"

            ledger.adopt(b)
            ledger.withdraw(b, hide)
            visible shouldBe null
        }

        test("a confirmation during a prompt is kept beneath it and shows when the wait ends") {
            val a = ledger.started()
            ledger.prompt(a)
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending") shouldBe true
            visible shouldBe "prompt"

            ledger.waitEnded()
            visible shouldBe "b pending"
        }

        test("a superseded attempt's unanswered prompt still leaves its notice, over the newer pending status") {
            val a = ledger.started()
            ledger.prompt(a)
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending")
            ledger.waitEnded()
            ledger.unanswered(a) shouldBe true
            visible shouldBe "not answered"

            ledger.swipe()
            visible shouldBe "b pending"
        }

        test("success of an older run leaves a newer queued request's pending status") {
            val a = ledger.started()
            ledger.progress(a, "a")
            val b = ledger.schedule {}
            ledger.confirmed(b, "b pending")
            ledger.clear(a, hide)
            visible shouldBe "b pending"
        }

        test("a worker that runs before its confirmation removes an older pending status and is not covered by its own") {
            val a = ledger.schedule {}
            ledger.confirmed(a, "a pending")
            val b = ledger.schedule {}
            ledger.adopt(b)
            visible shouldBe null
            ledger.confirmed(b, "b pending") shouldBe false
            ledger.progress(b, "b") shouldBe true
            visible shouldBe "b"
        }

        test("a notice is no longer news once a newer run shows progress, and is refused after one") {
            val a = ledger.started()
            ledger.unanswered(a) shouldBe true
            val b = ledger.started()
            visible shouldBe "not answered"
            ledger.progress(b, "b") shouldBe true
            visible shouldBe "b"

            ledger.unanswered(a) shouldBe false
            visible shouldBe "b"
        }

        // Order permutation: the events of each scenario reach the ledger from different threads
        // (the enqueue listener on the main executor, the workers, AdbClient's wait, the user), so
        // every interleaving must end with the same notification.

        test("every order: a replaced run's exit (API 31+) and its replacement's confirmation leave the replacement's status") {
            val setup: StartNotificationLedger.() -> Unit = {
                started() shouldBe 1L
                progress(1L, "a running")
                schedule {} shouldBe 2L
            }
            val results =
                everyOrder(
                    setup,
                    listOf(step("confirm b") { confirmed(2L, "b wifi") }),
                    listOf(step("a exits (REPLACE)") { withdraw(1L, hide) }),
                    then = listOf(step("swipe") { swipe() }),
                )
            results.size shouldBe 2
            results.allShow("b wifi", "b wifi")

            everyOrder(
                setup,
                listOf(step("confirm b") { confirmed(2L, "b wifi") }),
                listOf(step("a exits (REPLACE)") { withdraw(1L, hide) }),
                listOf(step("b adopts") { adopt(2L) }, step("b posts") { progress(2L, "b running") }),
            ).allShow("b running")
        }

        test("every order: a replaced run's retry (API 24-30) and its replacement's confirmation leave the replacement's own text") {
            everyOrder(
                {
                    started() shouldBe 1L
                    progress(1L, "a running")
                    schedule {} shouldBe 2L
                },
                listOf(step("confirm b") { confirmed(2L, "b wifi") }),
                listOf(step("a exits (retry)") { retry(1L, "a will retry") }),
            ).allShow("b wifi")
        }

        test("every order: an older attempt's prompt, its timeout and a newer confirmation leave the notice, then the newer status") {
            val setup: StartNotificationLedger.() -> Unit = {
                started() shouldBe 1L
                progress(1L, "a starting")
                schedule {} shouldBe 2L
            }
            val confirm = listOf(step("confirm b") { confirmed(2L, "b wifi") })

            val timedOut =
                everyOrder(
                    setup,
                    listOf(
                        step("a prompts") { prompt(1L) },
                        step("a's wait ends") { waitEnded() },
                        step("a unanswered") { unanswered(1L) },
                    ),
                    confirm,
                    then = listOf(step("swipe") { swipe() }),
                )
            timedOut.size shouldBe 4
            timedOut.allShow("not answered", "b wifi")

            everyOrder(
                setup,
                listOf(
                    step("a prompts") { prompt(1L) },
                    step("a's wait ends") { waitEnded() },
                    step("a exits (REPLACE)") { withdraw(1L, hide) },
                ),
                confirm,
            ).allShow("b wifi")

            everyOrder(
                setup,
                listOf(
                    step("a prompts") { prompt(1L) },
                    step("a's wait ends") { waitEnded() },
                    step("a succeeds") { clear(1L, hide) },
                ),
                confirm,
            ).allShow("b wifi")
        }

        test("every order: a confirmed, running, retried request keeps a pending status with its controls; a cancel ends it") {
            val setup: StartNotificationLedger.() -> Unit = { schedule {} shouldBe 1L }
            val confirm = listOf(step("confirm a") { confirmed(1L, "wifi required") })
            val run =
                listOf(
                    step("a adopts") { adopt(1L) },
                    step("a posts") { progress(1L, "running") },
                    step("a exits (retry)") { retry(1L, "will retry") },
                )

            everyOrder(setup, confirm, run, then = listOf(step("swipe") { swipe() }))
                .allShow("will retry", "will retry")

            val cancelled = everyOrder(setup, confirm, run, listOf(step("cancel") { cancel(hide) {} }), then = listOf(step("swipe") { swipe() }))
            cancelled.size shouldBe 20
            cancelled.allShow(null, null)
        }

        test("every order: a replacement whose enqueue failed, a confirmed one, and the running owner's success") {
            val setup: StartNotificationLedger.() -> Unit = {
                started() shouldBe 1L
                progress(1L, "a")
                schedule {} shouldBe 2L // its enqueue fails: nothing reaches the ledger
                schedule {} shouldBe 3L
            }
            everyOrder(
                setup,
                listOf(step("a succeeds") { clear(1L, hide) }),
                listOf(step("confirm c") { confirmed(3L, "c pending") }),
            ).allShow("c pending")

            everyOrder(
                setup,
                listOf(step("a succeeds") { clear(1L, hide) }),
                listOf(step("swipe") { swipe() }),
            ).allShow(null)
        }

        test("every order: two failed replacements leave the surviving owner's progress") {
            everyOrder(
                {
                    started() shouldBe 1L
                    progress(1L, "a")
                    schedule {} shouldBe 2L
                    schedule {} shouldBe 3L
                },
                listOf(step("a posts") { progress(1L, "a binder wait") }),
                listOf(step("swipe") { swipe() }),
            ).allShow("a binder wait")
        }

        test("every order: an older attempt's notice yields to a newer run's progress, success or prompt") {
            val setup: StartNotificationLedger.() -> Unit = {
                started() shouldBe 1L
                schedule {} shouldBe 2L
            }
            val timesOut =
                listOf(
                    step("a prompts") { prompt(1L) },
                    step("a's wait ends") { waitEnded() },
                    step("a unanswered") { unanswered(1L) },
                )

            everyOrder(
                setup,
                timesOut,
                listOf(step("b adopts") { adopt(2L) }, step("b posts") { progress(2L, "b running") }),
            ).allShow("b running")

            everyOrder(
                setup,
                listOf(step("a unanswered") { unanswered(1L) }),
                listOf(step("b adopts") { adopt(2L) }, step("b succeeds") { clear(2L, hide) }),
            ).allShow(null)

            everyOrder(
                setup,
                listOf(step("a unanswered") { unanswered(1L) }),
                listOf(step("b prompts") { prompt(2L) }, step("b's wait ends") { waitEnded() }),
            ).allShow(null)
        }

        test("a replacement that stands down leaves the holder's slots and no orphan of its own") {
            // A holds the dialog; B was submitted just before the wait began, stands down without
            // adopting, and its confirmation may land before or after its worker has ended.
            for (confirmFirst in listOf(true, false)) {
                visible = null
                stored = NO_ATTEMPT
                ledger = processLedger()
                val a = ledger.started()
                ledger.progress(a, "A running")
                ledger.prompt(a)
                val b = ledger.schedule {}
                if (confirmFirst) ledger.confirmed(b, "B pending")
                visible shouldBe "prompt"
                ledger.withdraw(b, hide)
                if (!confirmFirst) ledger.confirmed(b, "B pending") shouldBe false
                visible shouldBe "prompt"
                ledger.waitEnded()
                visible shouldBe "A running"
                ledger.withdraw(a, hide)
                visible shouldBe null
            }
        }

        test("a replacement that stands down also ends the queued request it replaced") {
            // A sits in backoff with its "will retry" status while an interactive start holds the
            // dialog. B replaces A's request, stands down, and nothing is queued any more.
            for (confirmFirst in listOf(true, false)) {
                visible = null
                stored = NO_ATTEMPT
                ledger = processLedger()
                val a = ledger.started()
                ledger.retry(a, "A will retry")
                visible shouldBe "A will retry"
                val b = ledger.schedule {}
                if (confirmFirst) ledger.confirmed(b, "B pending")
                ledger.withdraw(b, hide)
                if (!confirmFirst) ledger.confirmed(b, "B pending") shouldBe false
                visible shouldBe null
            }
        }
})
