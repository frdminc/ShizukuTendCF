package rikka.shizuku.server

import af.shizuku.server.IAIAutomationBridge
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class AICorePlusImplTest {
    private lateinit var service: ShizukuService
    private lateinit var runtimeMock: Runtime
    private lateinit var shell: Process
    private val shellInput = ByteArrayOutputStream()

    private fun impl() = AICorePlusImpl(mockk<ShizukuClientManager>(relaxed = true), service)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        service = mockk(relaxed = true)
        every { service.isPlusFeatureEnabled(any()) } returns true

        shell = mockk(relaxed = true)
        every { shell.outputStream } returns shellInput
        runtimeMock = mockk(relaxed = true)
        every { runtimeMock.exec("sh") } returns shell
        mockkStatic(Runtime::class)
        every { Runtime.getRuntime() } returns runtimeMock
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `simulateTouch writes an input tap to the persistent shell`() {
        assertTrue(impl().simulateTouch(100f, 200f))
        assertEquals("input tap 100.0 200.0\n", shellInput.toString())
    }

    @Test
    fun `simulateTouch returns false when the shell can't start`() {
        every { runtimeMock.exec("sh") } throws java.io.IOException("no sh")
        assertFalse(impl().simulateTouch(1f, 2f))
    }

    @Test
    fun `simulateTouch delegates to the automation bridge, not the shell`() {
        val bridge = mockk<IAIAutomationBridge>()
        every { bridge.simulateTouch(100.0f, 200.0f) } returns true
        val impl = impl()
        impl.setAutomationBridge(bridge)

        assertTrue("simulateTouch should return the automation bridge result", impl.simulateTouch(100.0f, 200.0f))
        verify(exactly = 0) { runtimeMock.exec(any<String>()) }
    }

    @Test
    fun `simulateTouch returns false when the automation bridge throws`() {
        val bridge = mockk<IAIAutomationBridge>()
        every { bridge.simulateTouch(100.0f, 200.0f) } throws RuntimeException("Mocked exception")
        val impl = impl()
        impl.setAutomationBridge(bridge)

        assertFalse("simulateTouch should return false when the automation bridge throws", impl.simulateTouch(100.0f, 200.0f))
    }

    @Test
    fun `simulateText passes untrusted text as one argv entry, never through sh`() {
        val text = "a; rm -rf / #"
        val proc = mockk<Process>(relaxed = true)
        every { runtimeMock.exec(arrayOf("input", "text", text)) } returns proc

        assertTrue(impl().simulateText(text))
        verify { runtimeMock.exec(arrayOf("input", "text", text)) }
        verify(exactly = 0) { runtimeMock.exec("sh") }
    }

    @Test
    fun `nothing is injected while experimental AI Core is off`() {
        every { service.isPlusFeatureEnabled("ai_core_experimental") } returns false
        assertFalse(impl().simulateTouch(1f, 2f))
        assertFalse(impl().simulateText("x"))
        verify(exactly = 0) { runtimeMock.exec(any<String>()) }
        verify(exactly = 0) { runtimeMock.exec(any<Array<String>>()) }
    }
}
