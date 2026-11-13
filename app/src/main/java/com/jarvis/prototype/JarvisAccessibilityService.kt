package com.jarvis.prototype

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility bridge for user-requested global phone actions.
 * This first version does not inspect screen contents or collect UI events.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeService: JarvisAccessibilityService? = null

        fun openSettings(): Boolean {
            return try {
                activeService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_SETTINGS) ?: false
            } catch (_: Exception) {
                false
            }
        }
    }
}
