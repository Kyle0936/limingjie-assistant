package com.landosol.toolbox.automation.accessibility

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWindowTrackingTest {
    @Test
    fun `state and content events both refresh foreground package`() {
        assertTrue(shouldRefreshForegroundWindow(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(shouldRefreshForegroundWindow(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(shouldRefreshForegroundWindow(AccessibilityEvent.TYPE_VIEW_CLICKED))
    }

    @Test
    fun `toolbox overlay window does not replace the foreground game`() {
        assertFalse(
            shouldTrackForegroundWindow(
                servicePackageName = "com.landosol.toolbox",
                eventPackageName = "com.landosol.toolbox",
                eventClassName = "android.view.View",
            ),
        )
    }

    @Test
    fun `toolbox activity and game activity remain trackable`() {
        assertTrue(
            shouldTrackForegroundWindow(
                servicePackageName = "com.landosol.toolbox",
                eventPackageName = "com.landosol.toolbox",
                eventClassName = "com.landosol.toolbox.MainActivity",
            ),
        )
        assertTrue(
            shouldTrackForegroundWindow(
                servicePackageName = "com.landosol.toolbox",
                eventPackageName = "com.bilibili.priconne",
                eventClassName = "com.bilibili.priconne.MainActivity",
            ),
        )
    }
}
