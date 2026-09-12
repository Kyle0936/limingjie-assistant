package com.landosol.toolbox.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCenterStateTest {
    @Test
    fun `notification control does not require overlay permission`() {
        val state = PermissionCenterState(
            accessibilityEnabled = true, accessibilityConnected = true,
            overlayEnabled = false, notificationsEnabled = true, captureSessionActive = true,
        )
        assertTrue(state.canStartVisualAutomation)
        assertFalse(state.copy(notificationsEnabled = false).canStartVisualAutomation)
    }

    @Test
    fun `visual automation requires every permission and capture session`() {
        val incomplete = PermissionCenterState(
            accessibilityEnabled = true,
            accessibilityConnected = true,
            overlayEnabled = true,
            notificationsEnabled = true,
            captureSessionActive = false,
        )
        val complete = incomplete.copy(captureSessionActive = true)

        assertFalse(incomplete.canStartVisualAutomation)
        assertTrue(complete.canStartVisualAutomation)
    }

    @Test
    fun `enabled accessibility setting is insufficient while service is disconnected`() {
        val disconnected = PermissionCenterState(
            accessibilityEnabled = true,
            accessibilityConnected = false,
            overlayEnabled = true,
            notificationsEnabled = true,
            captureSessionActive = true,
        )

        assertFalse(disconnected.canStartVisualAutomation)
        assertTrue(disconnected.copy(accessibilityConnected = true).canStartVisualAutomation)
    }
}
