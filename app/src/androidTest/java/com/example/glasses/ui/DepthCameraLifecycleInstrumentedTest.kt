package com.example.glasses.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.glasses.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DepthCameraLifecycleInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun livePipelineRecoversAfterBackgroundAndRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        runShellCommand("pm grant $packageName ${Manifest.permission.CAMERA}")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("YOLO26 Depth")

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForText("YOLO26 Depth")

            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            waitForText("YOLO26 Depth")

            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            waitForText("YOLO26 Depth")
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 120_000L) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForOrientation(
        scenario: ActivityScenario<MainActivity>,
        expected: Int,
    ) {
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            val orientation = AtomicInteger(Configuration.ORIENTATION_UNDEFINED)
            scenario.onActivity {
                orientation.set(it.resources.configuration.orientation)
            }
            orientation.get() == expected
        }
    }

    private fun runShellCommand(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        descriptor.close()
    }
}
