package com.yugahashimoto.andcode.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupProfileGenerator {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupAndChatNavigation() {
        rule.measureRepeated(
            packageName = "com.yugahashimoto.andcode",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wait(Until.hasObject(By.desc("Chat")), 10_000)
            device.findObject(By.desc("Chat"))?.click()
            device.waitForIdle()
        }
    }
}
