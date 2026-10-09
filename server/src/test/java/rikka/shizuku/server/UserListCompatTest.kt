package rikka.shizuku.server

import android.content.pm.UserInfo
import android.os.IUserManager
import android.os.RemoteException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The user list on every shape of {@code IUserManager.getUsers} (#27): the three-argument form of
 * API 30 to 16, the one-argument form Android 17 QPR1 went back to, and a framework with neither.
 */
class UserListCompatTest {
    private val users = listOf(user(0), user(10), user(11))

    @BeforeEach
    fun setUp() = UserListCompat.resetForTesting()

    @AfterEach
    fun tearDown() = UserListCompat.resetForTesting()

    @Test
    fun `api 16 keeps the three-argument call`() {
        UserListCompat.sdkInt = 36
        val um = mockk<IUserManager>()
        every { um.getUsers(true, true, true) } returns users
        every { um.getUsers(any()) } throws NoSuchMethodError("getUsers(Z)")

        assertEquals(setOf(0, 10, 11), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
        verify(exactly = 0) { um.getUsers(any()) }
    }

    @Test
    fun `android 17 qpr1 answers the one-argument call and every profile gets listed`() {
        UserListCompat.sdkInt = 37
        val um = mockk<IUserManager>()
        every { um.getUsers(any(), any(), any()) } throws NoSuchMethodError("No interface method getUsers(ZZZ)")
        every { um.getUsers(true) } returns users

        assertEquals(setOf(0, 10, 11), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
        // The form that worked is remembered: the second call does not probe the missing one again.
        assertEquals(setOf(0, 10, 11), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
        verify(exactly = 0) { um.getUsers(any(), any(), any()) }
    }

    @Test
    fun `a framework documented with three arguments that only has one still lists every user`() {
        UserListCompat.sdkInt = 36
        val um = mockk<IUserManager>()
        every { um.getUsers(any(), any(), any()) } throws NoSuchMethodError("No interface method getUsers(ZZZ)")
        every { um.getUsers(true) } returns users

        assertEquals(setOf(0, 10, 11), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
        verify(exactly = 1) { um.getUsers(any(), any(), any()) }
    }

    @Test
    fun `a remote failure is not a missing method`() {
        UserListCompat.sdkInt = 36
        val um = mockk<IUserManager>()
        every { um.getUsers(any(), any(), any()) } throws RemoteException("user service died")
        every { um.getUsers(any()) } returns users

        assertThrows(RemoteException::class.java) { UserListCompat.getUsers(um, true, true, true) }
        verify(exactly = 0) { um.getUsers(any()) }
        // As compat answered: user 0 alone rather than nobody.
        assertEquals(setOf(0), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
    }

    @Test
    fun `a framework with neither form falls back to user 0`() {
        UserListCompat.sdkInt = 37
        val um = mockk<IUserManager>()
        every { um.getUsers(any(), any(), any()) } throws NoSuchMethodError("getUsers(ZZZ)")
        every { um.getUsers(any()) } throws NoSuchMethodError("getUsers(Z)")

        assertEquals(setOf(0), UserListCompat.getUserIdsNoThrow(um, true, true, true).toSet())
    }

    private fun user(id: Int): UserInfo = UserInfo().also { it.id = id }
}
