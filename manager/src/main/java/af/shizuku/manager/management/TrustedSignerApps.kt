package af.shizuku.manager.management

import af.shizuku.common.util.TrustedSigners
import af.shizuku.manager.authorization.AuthorizationManager
import rikka.shizuku.Shizuku

/**
 * Asks the running server whether it always allows [uid] because a package in it is signed by a
 * key in [TrustedSigners], so the list shows that app as locked on instead of offering a switch
 * the server ignores. The server makes the decision itself (per uid, with uid ownership checked),
 * so a shared-uid sibling is judged exactly as it is enforced.
 *
 * Rides on the existing getFlagsForUid query via [TrustedSigners.FLAG_ALWAYS_ALLOWED] rather
 * than a new binder transaction. A server without the rule never sets that bit, so its rows are
 * ordinary switches, which is what it enforces.
 */
internal object TrustedSignerApps {
    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    enum class Authorization {
        NOT_GRANTED,
        GRANTED,
        ALWAYS_ALLOWED,

        /** The server could not be asked. Never treated as a grant that could be revoked. */
        UNRESOLVED,
        ;

        val granted: Boolean get() = this == GRANTED || this == ALWAYS_ALLOWED
        val locked: Boolean get() = this == ALWAYS_ALLOWED

        /** Only a state the server actually reported, and not locked, may offer grant or revoke. */
        val toggleable: Boolean get() = this == GRANTED || this == NOT_GRANTED
    }

    /**
     * Grant and lock state from one getFlagsForUid call, which the server answers from a single
     * signer lookup, so a row can never be shown locked beside a grant from a different answer.
     */
    fun authorization(
        packageName: String,
        uid: Int,
    ): Authorization {
        if (!awaitBinder()) return Authorization.UNRESOLVED
        // A pre-v11 server has no getFlagsForUid and no always-allowed rule, so its plain grant
        // state is the whole answer.
        if (Shizuku.isPreV11()) {
            return if (AuthorizationManager.granted(packageName, uid)) Authorization.GRANTED else Authorization.NOT_GRANTED
        }
        return try {
            val flags = Shizuku.getFlagsForUid(uid, MASK_PERMISSION or TrustedSigners.FLAG_ALWAYS_ALLOWED)
            when {
                (flags and FLAG_ALLOWED) == 0 -> Authorization.NOT_GRANTED
                (flags and TrustedSigners.FLAG_ALWAYS_ALLOWED) != 0 -> Authorization.ALWAYS_ALLOWED
                else -> Authorization.GRANTED
            }
        } catch (_: Throwable) {
            Authorization.UNRESOLVED
        }
    }

    // Fails to "not always allowed": the bulk revoke paths then still send the revoke, which the
    // server ignores for a uid it confirms trusted, rather than silently skip a non-trusted app.
    fun isAlwaysAllowed(uid: Int): Boolean =
        try {
            !Shizuku.isPreV11() &&
                (Shizuku.getFlagsForUid(uid, TrustedSigners.FLAG_ALWAYS_ALLOWED) and TrustedSigners.FLAG_ALWAYS_ALLOWED) != 0
        } catch (_: Throwable) {
            false
        }

    // Same back-off as AuthorizationManager.granted() (#398): a binder that drops right after a
    // consent broadcast is usually back within a few hundred ms.
    private fun awaitBinder(): Boolean {
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(200)
            val alive =
                try {
                    Shizuku.pingBinder()
                } catch (_: Throwable) {
                    false
                }
            if (alive) return true
        }
        return false
    }
}
