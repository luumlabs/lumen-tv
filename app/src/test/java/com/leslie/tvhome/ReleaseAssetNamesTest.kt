package com.luumlabs.lumen

import org.junit.Assert.*
import org.junit.Test

class ReleaseAssetNamesTest {
    @Test fun versionedAssetsPreferredInBothFlavors() {
        assertEquals("Lumen-v2.0.6.apk",
            ReleaseAssetNames.select("v2.0.6", false, listOf("Lumen.apk", "Lumen-v2.0.6.apk")))
        assertEquals("Lumen-guard-v2.0.6.apk",
            ReleaseAssetNames.select("v2.0.6", true, listOf("Lumen-guard.apk", "Lumen-guard-v2.0.6.apk")))
    }

    @Test fun historicalReleaseAssetsStillWork() {
        assertEquals("Lumen.apk", ReleaseAssetNames.select("v2.0.5", false, listOf("Lumen.apk")))
        assertEquals("Lumen-guard.apk", ReleaseAssetNames.select("v2.0.5", true, listOf("Lumen-guard.apk")))
    }

    @Test fun neverSelectWrongFlavorOrWrongVersion() {
        assertNull(ReleaseAssetNames.select("v2.0.6", true, listOf("Lumen-v2.0.6.apk")))
        assertNull(ReleaseAssetNames.select("v2.0.6", false, listOf("Lumen-v2.0.5.apk")))
    }
}
