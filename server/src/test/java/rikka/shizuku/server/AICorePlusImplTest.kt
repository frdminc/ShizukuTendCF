package rikka.shizuku.server

import af.shizuku.server.IAIAutomationBridge
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AICorePlusImplTest {

    private lateinit var aiCorePlusImpl: AICorePlusImpl
    private lateinit var serviceMock: ShizukuService

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        val clientManagerMock = mockk<ShizukuClientManager>(relaxed = true)
        serviceMock = mockk(relaxed = true)
        every { serviceMock.isPlusFeatureEnabled(any()) } returns true
        aiCorePlusImpl = AICorePlusImpl(clientManagerMock, serviceMock)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `simulateTouch returns false when experimental feature is disabled`() {
        every { serviceMock.isPlusFeatureEnabled(any()) } returns false

        assertFalse("simulateTouch should return false when the experimental feature is disabled",
            aiCorePlusImpl.simulateTouch(100.0f, 200.0f))
    }

    @Test
    fun `simulateTouch delegates to the automation bridge`() {
        val bridge = mockk<IAIAutomationBridge>()
        every { bridge.simulateTouch(100.0f, 200.0f) } returns true
        aiCorePlusImpl.setAutomationBridge(bridge)

        assertTrue("simulateTouch should return the automation bridge result",
            aiCorePlusImpl.simulateTouch(100.0f, 200.0f))
    }

    @Test
    fun `simulateTouch returns false when the automation bridge throws`() {
        val bridge = mockk<IAIAutomationBridge>()
        every { bridge.simulateTouch(100.0f, 200.0f) } throws RuntimeException("Mocked exception")
        aiCorePlusImpl.setAutomationBridge(bridge)

        assertFalse("simulateTouch should return false when the automation bridge throws",
            aiCorePlusImpl.simulateTouch(100.0f, 200.0f))
    }
}
