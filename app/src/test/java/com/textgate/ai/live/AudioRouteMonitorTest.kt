package com.textgate.ai.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRouteMonitorTest {

    private fun mockAudioDevice(type: Int): AudioDeviceInfo {
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.type).thenReturn(type)
        return device
    }

    private fun createMonitorWithDevices(outputs: Array<AudioDeviceInfo>): AudioRouteMonitor {
        val context = mock(Context::class.java)
        val appContext = mock(Context::class.java)
        val audioManager = mock(AudioManager::class.java)

        `when`(context.applicationContext).thenReturn(appContext)
        `when`(appContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(outputs)

        return AudioRouteMonitor(context)
    }

    @Test
    fun `PRIVATE_OUTPUT_TYPES does not contain Bluetooth SCO`() {
        assertFalse(
            "TYPE_BLUETOOTH_SCO must not be in PRIVATE_OUTPUT_TYPES for media audio",
            AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        )
    }

    @Test
    fun `PRIVATE_OUTPUT_TYPES contains A2DP and BLE audio devices`() {
        assertTrue(AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertTrue(AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(AudioRouteMonitor.PRIVATE_OUTPUT_TYPES.contains(AudioDeviceInfo.TYPE_USB_HEADSET))
    }

    @Test
    fun `selectPreferredOutputDevice selects A2DP over SCO when both are reported`() {
        val scoDevice = mockAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val a2dpDevice = mockAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val speakerDevice = mockAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        // SCO is reported first in the array, simulating Android HAL enumeration
        val monitor = createMonitorWithDevices(arrayOf(scoDevice, a2dpDevice, speakerDevice))

        val selected = monitor.selectPreferredOutputDevice()
        assertNotNull(selected)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, selected?.type)
        assertTrue(monitor.hasPrivateOutputRoute())
        assertEquals(AudioRouteMonitor.OutputRoute.BLUETOOTH, monitor.currentOutputRoute())
    }

    @Test
    fun `selectPreferredOutputDevice prioritizes wired headphones over Bluetooth`() {
        val a2dpDevice = mockAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val wiredDevice = mockAudioDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)

        val monitor = createMonitorWithDevices(arrayOf(a2dpDevice, wiredDevice))

        val selected = monitor.selectPreferredOutputDevice()
        assertNotNull(selected)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, selected?.type)
        assertEquals(AudioRouteMonitor.OutputRoute.WIRED, monitor.currentOutputRoute())
    }

    @Test
    fun `selectPreferredOutputDevice falls back to speaker when no private route is available`() {
        val speakerDevice = mockAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        val monitor = createMonitorWithDevices(arrayOf(speakerDevice))

        val selected = monitor.selectPreferredOutputDevice()
        assertNotNull(selected)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, selected?.type)
        assertFalse(monitor.hasPrivateOutputRoute())
        assertEquals(AudioRouteMonitor.OutputRoute.SPEAKER, monitor.currentOutputRoute())
    }
}
