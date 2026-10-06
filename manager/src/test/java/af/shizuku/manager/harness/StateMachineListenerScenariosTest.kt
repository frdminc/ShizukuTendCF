package af.shizuku.manager.harness

import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * State listeners run on the thread that makes a transition. A listener that throws (one touching
 * views off the main thread, say) used to abort the transition's side effects and the start that
 * made it; and a UI listener ran on whatever thread that was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StateMachineListenerScenariosTest {
    private lateinit var world: FakeWorld
    private val added = mutableListOf<(State) -> Unit>()

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ShizukuStateMachine.set(State.STOPPED)
    }

    @After
    fun tearDown() {
        added.forEach { ShizukuStateMachine.removeListener(it) }
        world.close()
    }

    private fun listen(listener: (State) -> Unit) {
        added += listener
        ShizukuStateMachine.addListener(listener)
    }

    private fun stateBroadcasts() =
        shadowOf(world.app).broadcastIntents.count { it.action == "af.shizuku.manager.action.STATE_CHANGED" }

    @Test
    fun `throwing-listener-does-not-stop-transition-or-broadcast`() {
        val seen = CopyOnWriteArrayList<State>()
        listen { throw IllegalStateException("listener bug") }
        listen { seen += it }
        val broadcastsBefore = stateBroadcasts()

        ShizukuStateMachine.set(State.STARTING)
        world.settle()

        assertEquals(State.STARTING, ShizukuStateMachine.get())
        assertEquals("the listener after the throwing one", listOf(State.STOPPED, State.STARTING), seen)
        assertEquals("STATE_CHANGED broadcasts", broadcastsBefore + 1, stateBroadcasts())
    }

    @Test
    fun `background-transition-reaches-listener-on-main-thread`() {
        val threads = CopyOnWriteArrayList<Thread>()
        val seen = CopyOnWriteArrayList<State>()
        listen {
            threads += Thread.currentThread()
            seen += it
        }
        threads.clear()
        seen.clear()

        Thread { ShizukuStateMachine.set(State.STARTING) }.apply {
            start()
            join()
        }
        assertEquals(State.STARTING, ShizukuStateMachine.get())
        world.settle()

        assertEquals(listOf(State.STARTING), seen)
        assertTrue("listener ran on ${threads.single()}", threads.single() === Looper.getMainLooper().thread)
    }

    @Test
    fun `main-thread-transition-queues-behind-earlier-background-ones`() {
        val seen = CopyOnWriteArrayList<State>()
        listen { seen += it }
        seen.clear()

        // A background transition is queued for the main thread; one made on the main thread
        // before the queue drains must not overtake it.
        Thread { ShizukuStateMachine.set(State.STARTING) }.apply {
            start()
            join()
        }
        ShizukuStateMachine.set(State.RUNNING)
        world.settle()

        assertEquals(listOf(State.STARTING, State.RUNNING), seen)
    }
}
