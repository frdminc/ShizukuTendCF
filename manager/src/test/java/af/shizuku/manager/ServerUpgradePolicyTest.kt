package af.shizuku.manager

import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.receiver.ServerUpgradePolicy
import af.shizuku.manager.receiver.ServerUpgradePolicy.Blocker
import af.shizuku.manager.receiver.ServerUpgradePolicy.Preconditions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerUpgradePolicyTest :
    FunSpec({

        val pkg = "moe.shizuku.privileged.api"
        val oldApk = "/data/app/~~old==/$pkg-aaa==/base.apk"
        val newApk = "/data/app/~~new==/$pkg-bbb==/base.apk"
        val otherFlavorApk = "/data/app/~~x==/af.shizuku.plus.api-ccc==/base.apk"

        // Every precondition met: the only inputs under which the old server is killed.
        val adbReady =
            Preconditions(
                primaryUser = true,
                launchMode = LaunchMethod.ADB,
                authWaitHeld = false,
                unansweredMarker = false,
                startInFlight = false,
                adbConnectedWithoutKeyOffer = true,
                rootAnswered = false,
            )
        val rootReady = adbReady.copy(launchMode = LaunchMethod.ROOT, adbConnectedWithoutKeyOffer = false, rootAnswered = true)

        test("all preconditions met: the starter runs") {
            ServerUpgradePolicy.blocker(adbReady) shouldBe null
            ServerUpgradePolicy.blocker(rootReady) shouldBe null
        }

        // Each failing precondition alone leaves the old server running.
        listOf(
            Triple("ADB: dialog held", adbReady.copy(authWaitHeld = true), Blocker.AUTH_WAIT_HELD),
            Triple("ADB: marker set", adbReady.copy(unansweredMarker = true), Blocker.AUTH_UNANSWERED),
            Triple("ADB: start in flight", adbReady.copy(startInFlight = true), Blocker.START_IN_FLIGHT),
            Triple("ADB: no no-key connection", adbReady.copy(adbConnectedWithoutKeyOffer = false), Blocker.NO_ADB_CONNECTION),
            Triple("ADB: secondary user", adbReady.copy(primaryUser = false), Blocker.SECONDARY_USER),
            Triple("unknown launch mode", adbReady.copy(launchMode = LaunchMethod.UNKNOWN), Blocker.NO_BACKGROUND_START),
            Triple("root: dialog held", rootReady.copy(authWaitHeld = true), Blocker.AUTH_WAIT_HELD),
            Triple("root: start in flight", rootReady.copy(startInFlight = true), Blocker.START_IN_FLIGHT),
            Triple("root: shell did not answer", rootReady.copy(rootAnswered = false), Blocker.NO_ROOT),
            Triple("root: secondary user", rootReady.copy(primaryUser = false), Blocker.SECONDARY_USER),
        ).forEach { (name, inputs, expected) ->
            test("no kill when $name") {
                ServerUpgradePolicy.blocker(inputs) shouldBe expected
            }
        }

        test("root starts ignore the ADB marker; ADB ignores the root shell") {
            ServerUpgradePolicy.blocker(rootReady.copy(unansweredMarker = true)) shouldBe null
            ServerUpgradePolicy.blocker(adbReady.copy(rootAnswered = true, adbConnectedWithoutKeyOffer = false)) shouldBe Blocker.NO_ADB_CONNECTION
            ServerUpgradePolicy.blocker(rootReady.copy(adbConnectedWithoutKeyOffer = true, rootAnswered = false)) shouldBe Blocker.NO_ROOT
        }

        test("the gates come before the route, so a blocked replacement opens no connection") {
            ServerUpgradePolicy.blocker(adbReady.copy(unansweredMarker = true, adbConnectedWithoutKeyOffer = false)) shouldBe Blocker.AUTH_UNANSWERED
            ServerUpgradePolicy.blocker(rootReady.copy(startInFlight = true, rootAnswered = false)) shouldBe Blocker.START_IN_FLIGHT
        }

        test("isStale: an earlier install of this package is stale, the installed APK is not") {
            ServerUpgradePolicy.isStale(oldApk, newApk, pkg) shouldBe true
            ServerUpgradePolicy.isStale(newApk, newApk, pkg) shouldBe false
            ServerUpgradePolicy.isStale(" $newApk\n", newApk, pkg) shouldBe false
        }

        test("isStale: unknown when it cannot be told") {
            ServerUpgradePolicy.isStale(null, newApk, pkg) shouldBe null
            ServerUpgradePolicy.isStale("", newApk, pkg) shouldBe null
            ServerUpgradePolicy.isStale(otherFlavorApk, newApk, pkg) shouldBe null
            ServerUpgradePolicy.isStale("/data/local/tmp/shizuku.apk", newApk, pkg) shouldBe null
            ServerUpgradePolicy.isStale("/data/app/~~old==/$pkg-aaa==/split.apk", newApk, pkg) shouldBe null
        }
    })
