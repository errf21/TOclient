package com.toclient.android.nativecore

import androidx.test.ext.junit.runners.AndroidJUnit4
import hev.htproxy.TProxyService
import libv2ray.Libv2ray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBindingsInstrumentedTest {
    @Test
    fun pinnedXrayAndHevBindingsLoad() {
        val xrayVersion = Libv2ray.checkVersionX()

        assertTrue(xrayVersion.contains("Xray-core"))
        assertFalse(TProxyService.TProxyIsRunning())
    }
}