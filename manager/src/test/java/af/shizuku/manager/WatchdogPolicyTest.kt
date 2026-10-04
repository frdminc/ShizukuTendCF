package af.shizuku.manager

import af.shizuku.manager.service.WatchdogPolicy
import af.shizuku.manager.utils.ShizukuStateMachine.State
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WatchdogPolicyTest :
    FunSpec({

        test("a deliberate stop is not restarted; a crash or failed start is") {
            WatchdogPolicy.restarts(State.STOPPED) shouldBe false
            WatchdogPolicy.restarts(State.CRASHED) shouldBe true
            WatchdogPolicy.restarts(State.STARTING) shouldBe false
            WatchdogPolicy.restarts(State.STOPPING) shouldBe false
            WatchdogPolicy.restarts(State.RUNNING) shouldBe false
        }

        test("a failed start (stale STARTING, nothing in flight) becomes CRASHED") {
            val past = WatchdogPolicy.STARTING_TIMEOUT_MS + 1
            WatchdogPolicy.staleStarting(past, authWaitHeld = false, startsRunning = 0) shouldBe State.CRASHED
            WatchdogPolicy.restarts(WatchdogPolicy.staleStarting(past, authWaitHeld = false, startsRunning = 0)) shouldBe true
        }

        test("a start still in flight stays STARTING, so the watchdog does not add a second one") {
            val past = WatchdogPolicy.STARTING_TIMEOUT_MS + 1
            WatchdogPolicy.staleStarting(past, authWaitHeld = true, startsRunning = 0) shouldBe State.STARTING
            WatchdogPolicy.staleStarting(past, authWaitHeld = false, startsRunning = 1) shouldBe State.STARTING
            WatchdogPolicy.staleStarting(WatchdogPolicy.STARTING_TIMEOUT_MS, authWaitHeld = false, startsRunning = 0) shouldBe State.STARTING
        }

        test("restarts follow the backoff: a failure inside the cooldown waits out the rest of it") {
            val now = 1_000_000L
            WatchdogPolicy.cooldownRemainingMs(now, lastRestartMs = 0L, consecutiveCrashes = 0) shouldBe 0L
            // Restart #1 at `now`; the start fails 3 s later: cooldown is now 10 s, 7 s remain.
            WatchdogPolicy.cooldownRemainingMs(now + 3_000L, lastRestartMs = now, consecutiveCrashes = 1) shouldBe 7_000L
            WatchdogPolicy.cooldownRemainingMs(now + 10_000L, lastRestartMs = now, consecutiveCrashes = 1) shouldBe 0L
        }

        test("the backoff doubles and is capped at 5 min, so a failing start cannot spin") {
            WatchdogPolicy.backoffMs(0) shouldBe 5_000L
            WatchdogPolicy.backoffMs(1) shouldBe 10_000L
            WatchdogPolicy.backoffMs(5) shouldBe 160_000L
            WatchdogPolicy.backoffMs(6) shouldBe 300_000L
            WatchdogPolicy.backoffMs(50) shouldBe 300_000L
            // Every restart after the first waits at least the base cooldown.
            (1..20).forEach { n ->
                (WatchdogPolicy.cooldownRemainingMs(5L, lastRestartMs = 5L, consecutiveCrashes = n) >= 5_000L) shouldBe true
            }
        }

        test("the unanswered marker withholds an ADB restart, not a root one") {
            WatchdogPolicy.restartWithheld(adbMode = true, unanswered = true) shouldBe true
            WatchdogPolicy.restartWithheld(adbMode = true, unanswered = false) shouldBe false
            WatchdogPolicy.restartWithheld(adbMode = false, unanswered = true) shouldBe false
        }
    })
