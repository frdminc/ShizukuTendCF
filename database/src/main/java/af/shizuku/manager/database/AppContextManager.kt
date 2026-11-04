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

    // Rebuilt whole and swapped, never mutated: the sync worker updates it while list rows read it.
    @Volatile private var merged: Map<String, AppMetadata>? = null
    @Volatile private var bundledRevision = 0
    @Volatile private var activeRevision = 0

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
