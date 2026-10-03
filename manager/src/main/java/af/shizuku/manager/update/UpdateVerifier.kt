package af.shizuku.manager.update

import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * SHA-256 check of a downloaded update APK against the `SHA256SUMS` asset that CI publishes
 * next to it in the same GitHub release. Fails closed: a missing, unreachable, malformed or
 * non-matching digest means the APK is not installed.
 *
 * This guards against a corrupted or truncated download and against a CDN/mirror serving bytes
 * that differ from what the release published. It shares the GitHub release as its trust root,
 * so it does NOT protect against someone who can edit release assets. Authenticity rests on
 * Android's same-signer rule for updates, which is why the updater never falls back to
 * uninstalling and reinstalling.
 */
object UpdateVerifier {
    private const val TAG = "UpdateVerifier"
    private const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/frdminc/ShizukuTendCF/releases/download/"
    private const val CHECKSUMS_ASSET = "SHA256SUMS"
    private const val MAX_CHECKSUMS_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private val SAFE_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")
    private val DIGEST_LINE = Regex("^([0-9a-fA-F]{64}) [ *](.+?)\\s*$")

    /** The `SHA256SUMS` URL in the same release as [downloadUrl], or null if it is not a release asset URL over HTTPS. */
    fun checksumsUrlFor(downloadUrl: String): String? {
        if (!downloadUrl.startsWith(RELEASE_DOWNLOAD_PREFIX)) return null
        // Exactly "<tag>/<asset>"; anything else (extra segments, query, fragment, traversal) is rejected.
        val parts = downloadUrl.removePrefix(RELEASE_DOWNLOAD_PREFIX).split('/')
        if (parts.size != 2 || parts.any { !SAFE_SEGMENT.matches(it) }) return null
        return RELEASE_DOWNLOAD_PREFIX + parts[0] + "/" + CHECKSUMS_ASSET
    }

    /** The asset file name of [downloadUrl], percent-decoded (matches the name in SHA256SUMS). */
    fun assetNameOf(downloadUrl: String): String? =
        try {
            URI(downloadUrl).path.substringAfterLast('/').takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    /** Finds [fileName]'s digest in `sha256sum` output ("<64 hex>  <name>" or "<64 hex> *<name>"). */
    fun parseDigest(
        sums: String,
        fileName: String,
    ): String? {
        val matches =
            sums
                .lineSequence()
                .mapNotNull { line -> DIGEST_LINE.find(line) }
                .filter { it.groupValues[2] == fileName }
                .map { it.groupValues[1].lowercase() }
                .toSet()
        // Conflicting entries for one name are treated as no usable digest.
        return matches.singleOrNull()
    }

    /**
     * Fetches the published digest for [downloadUrl]'s asset, hashes [downloaded] while copying
     * it to [staged], and returns [staged] only if the digests match. [staged] must be in
     * app-private storage: [downloaded] sits in external storage that other apps may be able to
     * write to, so the installer must use the copy whose bytes were the ones hashed (no
     * check-then-use gap). Blocking; call from an IO dispatcher. [staged] is deleted on failure.
     */
    fun verifyAndStage(
        downloaded: File,
        downloadUrl: String,
        staged: File,
    ): File? {
        try {
            val checksumsUrl = checksumsUrlFor(downloadUrl) ?: return reject("not a release asset URL: $downloadUrl")
            val name = assetNameOf(downloadUrl) ?: return reject("no asset name in $downloadUrl")
            val sums = fetchChecksums(checksumsUrl) ?: return reject("no $CHECKSUMS_ASSET available")
            val expected = parseDigest(sums, name) ?: return reject("no unique digest for $name")

            val md = MessageDigest.getInstance("SHA-256")
            downloaded.inputStream().use { input ->
                staged.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                staged.delete()
                return reject("digest mismatch for $name (expected $expected, got $actual)")
            }
            Timber.tag(TAG).i("SHA-256 verified for $name")
            return staged
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Update verification failed")
            staged.delete()
            return null
        }
    }

    private fun reject(reason: String): File? {
        Timber.tag(TAG).w("Refusing update: $reason")
        return null
    }

    private fun fetchChecksums(urlString: String): String? {
        val connection =
            (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Shizuku+")
            }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            // HttpURLConnection never follows an https -> http redirect, but check the final URL anyway.
            if (connection.url.protocol != "https") return null
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_CHECKSUMS_BYTES) return null
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
