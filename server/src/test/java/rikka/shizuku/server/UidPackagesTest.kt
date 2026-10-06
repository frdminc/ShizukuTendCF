package rikka.shizuku.server

import android.os.RemoteException
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reading that decides whether a UID's config entry, and with it the grant, is kept. The case a
 * phone cannot be asked to produce on demand is a read that fails: the no-throw lookup answered it
 * with an empty list, which pruning took for "uninstalled".
 */
class UidPackagesTest {

    private val threw = Callable<Array<String>?> { throw RemoteException("package manager not answering") }
    private fun answered(vararg names: String) = Callable<Array<String>?> { arrayOf(*names) }
    private val answeredNull = Callable<Array<String>?> { null }

    @Test
    fun `the user's list naming the uid means present, without asking the lookup`() {
        assertEquals(listOf("com.example.app"), UidPackages.of(listOf("com.example.app"), threw))
    }

    @Test
    fun `a lookup that threw keeps the entry even when the user's list lacks the uid`() {
        assertNull(UidPackages.of(emptyList(), threw))
    }

    @Test
    fun `a lookup that threw and an unreadable user list keep the entry`() {
        assertNull(UidPackages.of(null, threw))
    }

    @Test
    fun `an empty lookup with an unreadable user list keeps the entry`() {
        assertNull(UidPackages.of(null, answered()))
        assertNull(UidPackages.of(null, answeredNull))
    }

    @Test
    fun `gone only when the user's list was read and the lookup answered nothing`() {
        assertEquals(emptyList(), UidPackages.of(emptyList(), answered()))
        assertEquals(emptyList(), UidPackages.of(emptyList(), answeredNull))
    }

    @Test
    fun `a lookup that names packages means present whatever the user's list said`() {
        assertEquals(listOf("com.example.app"), UidPackages.of(emptyList(), answered("com.example.app")))
        assertEquals(listOf("com.example.app"), UidPackages.of(null, answered("com.example.app")))
    }
}
