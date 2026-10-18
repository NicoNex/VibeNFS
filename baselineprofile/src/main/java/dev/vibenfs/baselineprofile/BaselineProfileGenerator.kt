package dev.vibenfs.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the paths a person takes, so they ship precompiled: startup, the folder list, the editor
 * with its advanced fields and save menu, and back. Run with ./gradlew :app:generateBaselineProfile
 * on an English-language device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "dev.vibenfs", includeInStartupProfile = true) {
        // Android 17 asks for local network access on first launch; the dialog would cover the UI.
        device.executeShellCommand("pm grant $packageName android.permission.ACCESS_LOCAL_NETWORK")
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Add network folder")), 5_000)
        device.findObject(By.text("Add network folder"))?.click()
        device.wait(Until.hasObject(By.text("Advanced")), 5_000)
        repeat(2) {
            device.findObject(By.text("Advanced"))?.click()
            device.waitForIdle()
        }
        device.findObject(By.desc("More save options"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.pressBack()
        device.waitForIdle()
    }
}
