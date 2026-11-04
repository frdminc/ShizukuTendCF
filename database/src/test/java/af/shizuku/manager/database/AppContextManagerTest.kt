package af.shizuku.manager.database

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

class AppContextManagerTest :
    FunSpec({

        class FakeSettings(
            val bundled: String?,
            var remote: String? = null,
        ) : AppContextSettings {
            var lastUpdate = 0L

            override fun getBundledDbJson() = bundled

            override fun getRemoteDbJson() = remote

            override fun setRemoteDbJson(json: String) {
                remote = json
            }

            override fun setLastDbUpdate(time: Long) {
                lastUpdate = time
            }
        }

        val bundled =
            """
            {"revision": 2, "apps": {
              "a.swift": {"description": "Swift", "verified": true, "su_path_nav": "Grant in app", "shizuku_aware": true, "root_support": "partial"},
              "b.other": {"description": "Other"}
            }}
            """.trimIndent()

        test("the shipped database parses and keeps its SU-path hints") {
            val json = File("src/main/assets/app_context_db.json").readText()
            AppContextManager.initialize(FakeSettings(json))
            AppContextManager.appCount() shouldBeGreaterThan 70
            AppContextManager.getMetadata("org.swiftapps.swiftbackup")?.supportsShizukuNatively shouldBe true
            (AppContextManager.getMetadata("dev.ukanth.ufirewall")?.suPathSettingNav != null) shouldBe true
        }

        test("every enhancement key in app_context_db.json maps to an implemented global Plus feature") {
            val json = File("src/main/assets/app_context_db.json").readText()
            AppContextManager.validateEnhancementKeys(json) shouldBe emptyList()
            AppContextManager.initialize(FakeSettings(json))
            AppContextManager.getMetadata("org.adaway")?.recommendedPlusFeatures shouldBe listOf("network_governor_plus")
        }

        test("a newer download overrides only the fields it sets") {
            val s = FakeSettings(bundled)
            AppContextManager.initialize(s)
            AppContextManager.updateDatabase("""{"revision": 3, "apps": {"a.swift": {"description": "Swift v2"}, "c.new": {"description": "New"}}}""") shouldBe 2
            val swift = AppContextManager.getMetadata("a.swift")!!
            swift.description shouldBe "Swift v2"
            swift.suPathSettingNav shouldBe "Grant in app"
            swift.supportsShizukuNatively shouldBe true
            swift.rootSupportLevel shouldBe RootSupportLevel.PARTIAL
            AppContextManager.getMetadata("c.new")?.description shouldBe "New"
            AppContextManager.getMetadata("b.other")?.description shouldBe "Other"
            AppContextManager.revision() shouldBe 3
            (s.lastUpdate > 0) shouldBe true
        }

        test("an older download after a newer one is ignored, and unchanged apps aren't counted") {
            val s = FakeSettings(bundled)
            AppContextManager.initialize(s)
            AppContextManager.updateDatabase("""{"revision": 4, "apps": {"a.swift": {"description": "Swift v4"}, "b.other": {"description": "Other"}}}""") shouldBe 1
            AppContextManager.updateDatabase("""{"revision": 3, "apps": {"a.swift": {"description": "Swift v3"}}}""") shouldBe null
            AppContextManager.getMetadata("a.swift")?.description shouldBe "Swift v4"
            AppContextManager.revision() shouldBe 4
        }

        test("a download that isn't newer than the APK is ignored and not stored") {
            val s = FakeSettings(bundled)
            AppContextManager.initialize(s)
            AppContextManager.updateDatabase("""{"revision": 2, "apps": {"a.swift": {"description": "Old"}}}""") shouldBe null
            s.remote shouldBe null
            AppContextManager.getMetadata("a.swift")?.description shouldBe "Swift"
        }

        test("an old cached download (no revision) can't undo newer bundled text") {
            AppContextManager.initialize(FakeSettings(bundled, remote = """{"apps": {"a.swift": {"description": "Stale"}}}"""))
            AppContextManager.getMetadata("a.swift")?.description shouldBe "Swift"
            AppContextManager.revision() shouldBe 2
        }

        test("invalid JSON throws before anything is stored") {
            val s = FakeSettings(bundled)
            AppContextManager.initialize(s)
            runCatching { AppContextManager.updateDatabase("not json") }.isFailure shouldBe true
            s.remote shouldBe null
            AppContextManager.appCount() shouldBe 2
        }
    })
