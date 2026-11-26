package io.github.magnusencoded.stationtostation

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** An onboarded install reaches Settings without authoring any records for the fixture. */
@RunWith(AndroidJUnit4::class)
class HandoverNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val codeDescription = "The code the new phone reads"

    @Test fun startingASenderKeepsItsScreenAndOffersACode() {
        val settings = Intent(Intent.ACTION_VIEW, Uri.parse("station-to-station://settings"))
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settings)
        try {
            click("Move to a new phone")
            click("Your nights")
            click("Copy")
            waitUntil("sender offers a code") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (find(root, "Nothing moved") != null) fail("Starting the sender immediately showed Nothing moved")
                find(root, codeDescription) != null
            }
            val stableUntil = SystemClock.uptimeMillis() + 1_000
            while (SystemClock.uptimeMillis() < stableUntil) {
                assertNotNull("The sender must keep its code visible", find(instrumentation.uiAutomation.rootInActiveWindow, codeDescription))
                SystemClock.sleep(50)
            }
        } finally {
            context.startActivity(settings)
            waitUntil("leaving the sender reaches Settings") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "Settings") != null
            }
        }
    }

    private fun click(label: String) = waitUntil("clickable $label") {
        var target = find(instrumentation.uiAutomation.rootInActiveWindow, label)
        while (target != null && !target.isClickable) target = target.parent
        target != null && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun waitUntil(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        fail("Did not reach $description")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
