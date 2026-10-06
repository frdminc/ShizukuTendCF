package af.shizuku.manager.receiver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HeadlessAdbSummaryTest :
    FunSpec({
        val summary = HeadlessStartStopReceiver.Companion::adbSummary

        test("a listening TCP port is named with wireless debugging off (adb tcpip, or the app's restore)") {
            summary(true, false, 5555) shouldBe "USB:on WiFi:5555"
            summary(false, false, 5555) shouldBe "WiFi:5555"
        }

        test("wireless debugging on names the listening TCP port, or ? when none listens") {
            summary(true, true, 5555) shouldBe "USB:on WiFi:5555"
            summary(true, true, -1) shouldBe "USB:on WiFi:?"
        }

        test("nothing listening and wireless debugging off names no Wi-Fi") {
            summary(true, false, -1) shouldBe "USB:on"
            summary(false, false, -1) shouldBe "off"
            summary(false, false, 0) shouldBe "off"
        }
    })
