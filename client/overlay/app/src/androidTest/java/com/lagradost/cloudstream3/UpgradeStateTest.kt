package com.lagradost.cloudstream3

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UpgradeStateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("kadrograd_upgrade_check", 0)
    private val file get() = context.getFileStreamPath("kadrograd-upgrade-check.txt")

    @Test fun markBeforeUpgrade() {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertTrue(preferences.edit().putLong("version", version).putInt("uid", context.applicationInfo.uid)
            .putString("value", "preserved").commit())
        file.writeText("retained download metadata")
    }

    @Test fun assertAfterUpgrade() {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertTrue("Expected a newer app version", version > preferences.getLong("version", Long.MAX_VALUE))
        assertEquals(preferences.getInt("uid", -1), context.applicationInfo.uid)
        assertEquals("preserved", preferences.getString("value", null))
        assertEquals("retained download metadata", file.readText())
        preferences.edit().clear().commit()
        file.delete()
    }
}
