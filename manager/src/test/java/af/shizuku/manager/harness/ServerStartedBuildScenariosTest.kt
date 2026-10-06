package af.shizuku.manager.harness

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which app build started the running server feeds isServerVersionSkewed(), the "restart the
 * server after an update" prompt. It used to be recorded when a start began, so a restart that
 * failed while the pre-update server stayed alive marked that old server as current.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServerStartedBuildScenariosTest {
    private lateinit var world: FakeWorld
    private val olderBuild = BuildConfig.VERSION_CODE - 1

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
    }

    @After
    fun tearDown() = world.close()

    /** A server from the build before this one is running, as after an app update. */
    private fun olderServerRunning() {
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuSettings.setServerStartedBuild(olderBuild)
        assertTrue("precondition: skewed", ShizukuStateMachine.isServerVersionSkewed())
    }

    @Test
    fun `failed-restart-over-older-server-stays-skewed`() {
        olderServerRunning()

        ShizukuStateMachine.set(State.STARTING)
        // The start fails; the old server never went away, so update() settles back onto it.
        assertEquals(State.RUNNING, ShizukuStateMachine.update())

        assertEquals(olderBuild, ShizukuSettings.getServerStartedBuild())
        assertTrue(ShizukuStateMachine.isServerVersionSkewed())
    }

    @Test
    fun `successful-restart-records-this-build`() {
        olderServerRunning()

        ShizukuStateMachine.set(State.STARTING)
        // The starter replaces the old server with a new one, which brings a new binder.
        world.serverDown()
        world.serverUp()
        assertEquals(State.RUNNING, ShizukuStateMachine.update())

        assertEquals(BuildConfig.VERSION_CODE, ShizukuSettings.getServerStartedBuild())
        assertFalse(ShizukuStateMachine.isServerVersionSkewed())
    }

    // HomeActivity's RUNNING listener runs the version-skew check. On the main thread (where the
    // binder-received callback sets RUNNING) listeners are called inline, so they must run after
    // the build is recorded, or the check offers a "restart" that stops the new server.
    @Test
    fun `running-listener-sees-the-restart-recorded`() {
        olderServerRunning()
        val skewSeenByListener = mutableListOf<Boolean>()
        val listener: (State) -> Unit = { if (it == State.RUNNING) skewSeenByListener += ShizukuStateMachine.isServerVersionSkewed() }
        ShizukuStateMachine.addListener(listener)
        try {
            skewSeenByListener.clear()
            ShizukuStateMachine.set(State.STARTING)
            world.serverDown()
            world.serverUp()
            ShizukuStateMachine.set(State.RUNNING)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("the listener saw RUNNING once, after the build was recorded", listOf(false), skewSeenByListener)
        } finally {
            ShizukuStateMachine.removeListener(listener)
        }
    }

    @Test
    fun `start-that-never-succeeds-records-nothing`() {
        ShizukuSettings.setServerStartedBuild(olderBuild)
        ShizukuStateMachine.set(State.STARTING)
        ShizukuStateMachine.set(State.STOPPED)

        assertEquals(olderBuild, ShizukuSettings.getServerStartedBuild())
    }

    @Test
    fun `start-that-times-out-but-comes-up-late-is-recorded`() {
        ShizukuSettings.setServerStartedBuild(olderBuild)
        ShizukuStateMachine.set(State.STARTING)
        ShizukuStateMachine.set(State.STOPPED)
        world.serverUp()
        assertEquals(State.RUNNING, ShizukuStateMachine.update())

        assertEquals(BuildConfig.VERSION_CODE, ShizukuSettings.getServerStartedBuild())
    }
}
