package af.shizuku.manager.database

import timber.log.Timber

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class RootSupportLevel {
    FULL,
    PARTIAL,
    ROOT_REQUIRED
}

interface AppContextSettings {
    /** The app_context_db.json shipped in the APK (assets). */
    fun getBundledDbJson(): String?
    fun getRemoteDbJson(): String?
    fun setRemoteDbJson(json: String)
    fun setLastDbUpdate(time: Long)
}

/**
 * App descriptions, SU-path hints and root-support levels shown in the Apps list and the Root
 * Compatibility hub.
 *
 * One source: `database/src/main/assets/app_context_db.json`. It ships in the APK and is also the
 * file [RemoteDbSyncWorker] downloads from GitHub, so editing it on master reaches installed apps
 * without a release. A downloaded copy only applies when its `revision` is higher than the bundled
 * one, and only the fields it sets override the bundled entry - a remote entry without
 * `su_path_nav` keeps the bundled hint.
 */
object AppContextManager {

    /**
     * Real server-backed global Plus features that an app in `app_context_db.json` may reference
     * in its `enhancements` list (#631).
     */
    val SUPPORTED_PLUS_FEATURES: Set<String> = setOf(
        "shell_interceptor",
        "storage_proxy",
        "overlay_manager_plus",
        "network_governor_plus"
    )

    @Serializable
    data class AppMetadata(
        val description: String,
        val isVerified: Boolean = false,
        val suPathSettingNav: String? = null,
        val rootSupportLevel: RootSupportLevel = RootSupportLevel.FULL,
        val supportsShizukuNatively: Boolean = false,
        val recommendedPlusFeatures: List<String> = emptyList()
    )

    @Serializable
    private data class DbFile(
        val revision: Int = 0,
        val apps: Map<String, DbEntry> = emptyMap()
    )

    // Every field optional so a remote entry can override just what it sets.
    @Serializable
    private data class DbEntry(
        val description: String? = null,
        val verified: Boolean? = null,
        @SerialName("su_path_nav") val suPathNav: String? = null,
        @SerialName("root_support") val rootSupport: String? = null,
        @SerialName("shizuku_aware") val shizukuAware: Boolean? = null,
        @SerialName("enhancements") val enhancements: List<String>? = null
    )

    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var settings: AppContextSettings? = null

<<<<<<< HEAD
    private val staticDatabase = mutableMapOf<String, AppMetadata>().apply {
        // --- Core Root & Modding ---
        put("com.topjohnwu.magisk", AppMetadata("Magisk: The systemless root solution. ShizukuTendCF spoofs its presence to other apps.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL))
        put("eu.chainfire.supersu", AppMetadata("SuperSU: Legacy root solution. ShizukuTendCF spoofs its presence to other apps for maximum legacy compatibility.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL))
        put("org.lsposed.manager", AppMetadata("LSPosed: Xposed framework modern implementation. ShizukuTendCF successfully masks its presence.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL))
        put("com.vipercn.viper4android_v2", AppMetadata("ViPER4Android FX: Audio effects engine. Driver installation requires real root, but basic setup is mockable.", listOf(ENH_SHELL), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED))

        // --- Legacy Root Apps ---
        put("org.adaway", AppMetadata("AdAway: Open-source ad blocker. SU path auto-configured; use 'Network Governor' in ShizukuTendCF for rootless DNS blocking.", listOf(ENH_SHELL, ENH_NETWORK), true))
        put("dev.ukanth.ufirewall", AppMetadata("AFWall+: Firewall app. Fully functional rootless under ShizukuTendCF via automatic local iptables fallback mocking.", listOf(ENH_SHELL, ENH_NETWORK), true, "Menu > Preferences > SU path", rootSupportLevel = RootSupportLevel.FULL))
        put("com.ramdaas.ramexe", AppMetadata("RamExe: RAM manager and process killer. SU path auto-configured via Android global settings (no root needed).", listOf(ENH_SHELL), true))
        put("me.piebridge.prevent", AppMetadata("Prevent: Blocks apps from auto-starting to reduce memory and data leakage. SU path auto-configured via Android global settings.", listOf(ENH_SHELL), true))
        put("com.samsung.android.hexinstall", AppMetadata("Hex Installer: Theming engine for Samsung. ShizukuTendCF provides the necessary Overlay Bridge for OneUI 8+.", listOf(ENH_WIN, ENH_OVERLAY), true))
        put("com.samsung.android.themepark", AppMetadata("Theme Park: Official Samsung customization. Enhanced by ShizukuTendCF Overlay API.", listOf(ENH_WIN, ENH_OVERLAY), true))
        put("com.keramidas.TitaniumBackup", AppMetadata("Titanium Backup: App data backup and restore fully works via SU Bridge using native 'bu' mapping.", emptyList(), true, "Menu > More > Preferences > su executable path", rootSupportLevel = RootSupportLevel.FULL))
        put("eu.darken.sdm", AppMetadata("SD Maid (Legacy): Fully functional via SU Bridge; deep system paths and shell execution are safely routed.", emptyList(), true, "Settings > Root > Binary path", rootSupportLevel = RootSupportLevel.FULL))
        put("com.speedsoftware.rootexplorer", AppMetadata("Root Explorer: File manager with elevated access. SU path auto-configured; Storage Bridge handles deep system browsing.", listOf(ENH_STORAGE), true, "Settings > Root access > SU path", rootSupportLevel = RootSupportLevel.FULL))
        put("com.speedsoftware.explorer", AppMetadata("Speed Explorer: File manager by Speed Software.", listOf(ENH_STORAGE), true, rootSupportLevel = RootSupportLevel.FULL))
        put("com.jrummy.root.browserfree", AppMetadata("Root Browser: File manager with elevated access. SU path auto-configured; Storage Bridge handles system browsing.", listOf(ENH_STORAGE), true, "Settings > Superuser > SU binary path", rootSupportLevel = RootSupportLevel.FULL))
        put("com.estrongs.android.pop", AppMetadata("ES File Explorer: All-in-one file manager. SU path auto-configured via shared_prefs when running as root.", listOf(ENH_STORAGE), true, "Tools > Root Explorer > su path", rootSupportLevel = RootSupportLevel.FULL))
        put("com.github.machiav3lli.backup", AppMetadata("OAndBackupX: Open-source backup for root users. SU path auto-configured via shared_prefs.", listOf(ENH_STORAGE, ENH_SHELL), true, "Preferences > Advanced > Custom shell", rootSupportLevel = RootSupportLevel.ROOT_REQUIRED))
        put("com.jrummy.apps.build.prop.editor", AppMetadata("BuildProp Editor: Edit system properties. Fully functional rootless under ShizukuTendCF via build.prop shadow-copy redirection.", emptyList(), true, rootSupportLevel = RootSupportLevel.FULL))
        put("com.machiav3lli.neo_backup", AppMetadata("Neo Backup: Modern open-source backup solution.", listOf(ENH_STORAGE), true, "Preferences > Advanced > Custom shell"))
        put("projekt.substratum.lite", AppMetadata("Substratum Lite: Theming engine for Android.", listOf(ENH_WIN), true))
        put("com.oasisfeng.greenify", AppMetadata("Greenify: Maximize battery savings by hibernating apps.", listOf(ENH_SHELL), true))
        put("com.franco.doze", AppMetadata("Naptime: Aggressive Doze for better battery life.", listOf(ENH_SHELL), true))
        put("com.uzumapps.wakelockdetector", AppMetadata("Wakelock Detector: Find apps draining your battery.", listOf(ENH_SHELL), true))
        put("com.asksven.betterbatterystats", AppMetadata("BetterBatteryStats: Deep dive into battery drain.", listOf(ENH_SHELL), true))
        put("org.swiftapps.swiftbackup", AppMetadata("Swift Backup: Uses native Shizuku integration — no SU Bridge path needed. Grant access in App Management, then use its own \"Grant with Root or Shizuku\" option.", listOf(ENH_STORAGE, ENH_SHELL), true, suPathSettingNav = "Shizuku-native: no SU path setting. Use Swift Backup's \"Grant with Root or Shizuku\" flow.", supportsShizukuNatively = true))

        // --- thejaustin's Apps ---
        put("thejaustin.hexodus", AppMetadata("Hexodus: Spiritual successor to Hex Installer for OneUI 8.", listOf(ENH_SHELL, ENH_WIN), true))
        put("thejaustin.afdroid", AppMetadata("afdroid: Expressive F-Droid client.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.pearity", AppMetadata("Pearity: iOS parity settings for OneUI.", listOf(ENH_SHELL), true))
        put("thejaustin.termux_ai", AppMetadata("Termux AI: AI-powered terminal with NPU integration.", listOf(ENH_NPU, ENH_VM, ENH_SHELL), true))
        put("thejaustin.appmanager", AppMetadata("AppManager: Advanced package manager with archiving.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.simweather", AppMetadata("SimWeather: Material You weather tool.", listOf(ENH_SHELL), true))
        put("thejaustin.contactsplus", AppMetadata("ContactsPlus: Fossify Contacts fork with M3 design.", listOf(ENH_SHELL), true))
        put("thejaustin.obtainiumplus", AppMetadata("ObtainiumPlus: AI-assisted Obtainium fork.", listOf(ENH_SHELL), true))
        put("thejaustin.snapback", AppMetadata("SnapBack: Secure Snapchat Backup Viewer.", listOf(ENH_STORAGE), true))

        // --- Software Management & Freezers ---
        put("com.aistra.hail", AppMetadata("Hail: Modern app freezer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("samolego.canta", AppMetadata("Canta: Powerful system app debloater.", listOf(ENH_SHELL), true))
        put("rikka.appops", AppMetadata("App Ops: Manage hidden app permissions.", listOf(ENH_SHELL), true))
        put("com.catchingnow.icebox", AppMetadata("Ice Box: Freeze apps to save battery.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.zacharee.installwithoptions", AppMetadata("InstallWithOptions: Advanced APK installer.", listOf(ENH_SHELL), true))
        put("cf.playhi.freezeyou", AppMetadata("FreezeYou: Battery and speed optimizer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.oasisfeng.island", AppMetadata("Island: App isolation and cloning.", listOf(ENH_DPM, ENH_SHELL), true))
        put("com.oasisfeng.island.fdroid", AppMetadata("Insular: Island fork for F-Droid.", listOf(ENH_DPM, ENH_SHELL), true))

        // --- File Management ---
        put("bin.mt.plus", AppMetadata("MT Manager: Sophisticated file manager.", listOf(ENH_STORAGE), true))
        put("pl.solidexplorer2", AppMetadata("Solid Explorer: Powerful file manager. SU path auto-configured via shared_prefs when running as root.", listOf(ENH_STORAGE), true, "Settings > Root access > Root binary"))
        put("com.ghisler.android.TotalCommander", AppMetadata("Total Commander: Desktop-class file explorer. SU path auto-configured via shared_prefs when running as root.", listOf(ENH_STORAGE), true, "Config > Root path"))
        put("com.lonelycatgames.Xplore", AppMetadata("X-Plore: Dual-pane file manager.", listOf(ENH_STORAGE), true))
        put("ru.zdevs.zarchiver", AppMetadata("ZArchiver: Comprehensive archive manager.", listOf(ENH_STORAGE), true))
        put("com.alphainventor.filemanager", AppMetadata("File Manager Plus: Cloud and local explorer.", listOf(ENH_STORAGE), true))

        // --- Automation ---
        put("net.dinglisch.android.taskerm", AppMetadata("Tasker: Advanced Android automation.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.arlosoft.macrodroid", AppMetadata("MacroDroid: User-friendly automation.", listOf(ENH_SHELL), true))
        put("henrichg.phoneprofilesplus", AppMetadata("PhoneProfilesPlus: Contextual device config.", listOf(ENH_SHELL), true))
        put("eu.toneiv.ubktouch", AppMetadata("UbikiTouch: Global swipe gestures.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Customization ---
        put("com.kieronquinn.ambientmusicmod", AppMetadata("Ambient Music Mod: Now Playing for everyone.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.darq", AppMetadata("DarQ: Per-app force dark mode.", listOf(ENH_SHELL), true))
        put("com.zacharee.tweaker", AppMetadata("System UI Tuner: Hidden system settings.", listOf(ENH_SHELL), true))
        put("dev.lexip.hecate", AppMetadata("Adaptive-Theme: Smart dark mode.", listOf(ENH_SHELL), true))
        put("mahmud0808.colorblendr", AppMetadata("ColorBlendr: Material You color editor.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.smartspacer", AppMetadata("Smartspacer: Enhanced 'At a Glance' widget.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Network & Privacy ---
        put("com.ysy.app.firewall", AppMetadata("NetWall: Rootless app firewall.", listOf(ENH_SHELL), true))
        put("com.deltazefiro.amarokhider", AppMetadata("Amarok: Hide private files and apps.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("ahmetcanarslan.shizuwall", AppMetadata("ShizuWall: Open-source app firewall.", listOf(ENH_SHELL), true))
        put("tk.zwander.wifilist", AppMetadata("WiFiList: View saved WiFi passwords.", listOf(ENH_SHELL), true))

        // --- Tools & Terminals ---
        put("p.shashank.ashellyou", AppMetadata("aShell You: Material local ADB shell.", listOf(ENH_SHELL), true))
        put("rohitkushvaha01.reterminal", AppMetadata("ReTerminal: Material 3 terminal emulator.", listOf(ENH_SHELL, ENH_VM), true))
        put("com.imranr98.obtainium", AppMetadata("Obtainium: App updates from source.", listOf(ENH_SHELL), true))
        put("com.aurora.store", AppMetadata("Aurora Store: Privacy Play Store client.", listOf(ENH_SHELL), true))
        put("com.looker.droidify", AppMetadata("Droid-ify: Material F-Droid client.", listOf(ENH_SHELL), true))
        put("eu.darken.sdmse", AppMetadata("SD Maid SE: System cleaning tool.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.paget96.chargemonitor", AppMetadata("Battery Charge Limit: Cap charge % to preserve battery health. Needs Shizuku with root to write charge limit sysfs.", emptyList(), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED))
        put("com.mihonapp.mihon", AppMetadata("Mihon: Manga reader and extension manager.", listOf(ENH_SHELL), true))
    }
=======
    // Rebuilt whole and swapped, never mutated: the sync worker updates it while list rows read it.
    @Volatile private var merged: Map<String, AppMetadata>? = null
    @Volatile private var bundledRevision = 0
    @Volatile private var activeRevision = 0
>>>>>>> upstream/master

    fun initialize(settings: AppContextSettings) {
        this.settings = settings
        merged = null
        // Build off the main thread now, so the first Apps-list row or settings summary doesn't
        // read the asset and decode the JSON on the UI thread.
        Thread({ runCatching { database() } }, "AppDbWarmup").start()
    }

    fun getMetadata(packageName: String): AppMetadata? = database()[packageName]

    /** Number of apps known, and the revision in use (bundled or a newer download). */
    fun appCount(): Int = database().size

    fun revision(): Int {
        database()
        return activeRevision
    }

    fun getRootLegacyPackages(): Map<String, List<String>> {
        return mapOf(
            "Backup & Cleaning" to listOf(
                "com.keramidas.TitaniumBackup",
                "eu.darken.sdm",
                "org.swiftapps.swiftbackup",
                "com.machiav3lli.backup",
                "com.github.machiav3lli.backup"
            ),
            "File Management" to listOf(
                "com.speedsoftware.rootexplorer",
                "com.jrummy.root.browserfree",
                "pl.solidexplorer2",
                "com.ghisler.android.TotalCommander",
                "com.estrongs.android.pop"
            ),
            "System Customization" to listOf(
                "projekt.substratum.lite",
                "com.zacharee.tweaker",
                "com.samsung.android.themepark",
                "com.samsung.android.hexinstall"
            ),
            "Battery & Optimization" to listOf(
                "com.oasisfeng.greenify",
                "com.franco.doze",
                "com.paget96.chargemonitor",
                "com.ramdaas.ramexe"
            ),
            "Privacy & Security" to listOf(
                "org.adaway",
                "dev.ukanth.ufirewall",
                "com.uzumapps.wakelockdetector",
                "me.piebridge.prevent"
            ),
            "Advanced Tools" to listOf(
                "com.asksven.betterbatterystats",
                "com.jrummy.apps.build.prop.editor"
            )
        )
    }
    
    fun getDescription(packageName: String): String? = getMetadata(packageName)?.description

    /**
     * Applies a downloaded database. Returns how many apps' info actually changed, or null when
     * it is not newer than the revision in use (then it isn't stored - a stale CDN copy can't roll
     * the data back). Throws on invalid JSON, before anything is stored.
     */
    fun updateDatabase(json: String): Int? {
        val remote = jsonConfig.decodeFromString<DbFile>(json)
        val before = database()
        if (remote.revision <= activeRevision) return null
        settings?.setRemoteDbJson(json)
        settings?.setLastDbUpdate(System.currentTimeMillis())
        merged = null
        val after = database()
        return (before.keys + after.keys).count { before[it] != after[it] }
    }

    private fun database(): Map<String, AppMetadata> {
        merged?.let { return it }
        synchronized(this) {
            merged?.let { return it }
            val built = build()
            merged = built
            return built
        }
    }

    private fun build(): Map<String, AppMetadata> {
        val s = settings ?: return emptyMap()
        val bundled = parse(s.getBundledDbJson(), "bundled") ?: DbFile()
        bundledRevision = bundled.revision
        activeRevision = bundled.revision
        val result = LinkedHashMap<String, AppMetadata>()
        bundled.apps.forEach { (pkg, e) -> e.toMetadata(null)?.let { result[pkg] = it } }
        val remote = parse(s.getRemoteDbJson(), "downloaded")
        // An older download (or one cached before revisions existed) would undo newer bundled text.
        if (remote != null && remote.revision > bundled.revision) {
            remote.apps.forEach { (pkg, e) -> e.toMetadata(result[pkg])?.let { result[pkg] = it } }
            activeRevision = remote.revision
        }
        return result
    }

    private fun parse(json: String?, what: String): DbFile? {
        if (json.isNullOrBlank()) return null
        return try {
            jsonConfig.decodeFromString<DbFile>(json)
        } catch (e: Exception) {
            Timber.e(e, "AppContextManager: %s app database is invalid", what)
            null
        }
    }

    /**
     * Validates that every key in `enhancements` across all entries in [json] is a member of
     * [SUPPORTED_PLUS_FEATURES]. Returns the list of unknown keys (`pkg:key`), or empty if valid.
     */
    fun validateEnhancementKeys(json: String): List<String> {
        val parsed = jsonConfig.decodeFromString<DbFile>(json)
        val unknown = mutableListOf<String>()
        parsed.apps.forEach { (pkg, entry) ->
            entry.enhancements?.forEach { key ->
                if (key !in SUPPORTED_PLUS_FEATURES) {
                    unknown.add("$pkg:$key")
                }
            }
        }
        return unknown
    }

    private fun DbEntry.toMetadata(base: AppMetadata?): AppMetadata? {
        val desc = description ?: base?.description ?: return null
        val features = (enhancements ?: base?.recommendedPlusFeatures ?: emptyList())
            .filter { it in SUPPORTED_PLUS_FEATURES }
        return AppMetadata(
            description = desc,
            isVerified = verified ?: base?.isVerified ?: false,
            suPathSettingNav = suPathNav ?: base?.suPathSettingNav,
            rootSupportLevel = rootSupport?.let {
                when (it.lowercase()) {
                    "partial" -> RootSupportLevel.PARTIAL
                    "required" -> RootSupportLevel.ROOT_REQUIRED
                    else -> RootSupportLevel.FULL
                }
            } ?: base?.rootSupportLevel ?: RootSupportLevel.FULL,
            supportsShizukuNatively = shizukuAware ?: base?.supportsShizukuNatively ?: false,
            recommendedPlusFeatures = features
        )
    }
}
