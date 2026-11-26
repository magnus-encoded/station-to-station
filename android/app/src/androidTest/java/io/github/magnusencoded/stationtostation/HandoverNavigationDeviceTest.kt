package io.github.magnusencoded.stationtostation

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an onboarded debug install; offers Nights only and closes before any receiver joins. */
@RunWith(AndroidJUnit4::class)
class HandoverNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun startingASenderKeepsItsScreenAndOffersACode() {
        val settings = Intent(Intent.ACTION_VIEW, Uri.parse("station-to-station://settings"))
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settings)
        try {
            click("Move to a new phone")
            click("Your nights")
            click("Copy")
            awaitNode("The code the new phone reads")
        } finally {
            context.startActivity(settings)
        }
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var target = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(50)
        }
        error("Control did not become clickable: $label")
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, label)?.let { return it }
            if (label == "The code the new phone reads" && find(root, "Nothing moved") != null) {
                fail("Starting the sender immediately showed Nothing moved")
            }
            SystemClock.sleep(50)
        }
        error("Did not reach $label; starting a transfer must keep the sender alive")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
