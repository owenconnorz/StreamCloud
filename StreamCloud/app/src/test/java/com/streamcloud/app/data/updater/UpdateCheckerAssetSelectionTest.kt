package com.streamcloud.app.data.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerAssetSelectionTest {
    private val tvUniversal = "StreamCloud-tv-release.apk"
    private val tvArm64 = "StreamCloud-tv-arm64-v8a-release.apk"
    private val tvArm32 = "StreamCloud-tv-armeabi-v7a-release.apk"
    private val mobile = "StreamCloud-mobile-release.apk"

    @Test
    fun tvPrefersArm64SplitEvenWhenUniversalComesFirst() {
        assertEquals(
            tvArm64,
            selectReleaseApkAssetName(
                assetNames = listOf(tvUniversal, tvArm32, tvArm64),
                isTv = true,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            ),
        )
    }

    @Test
    fun tvUsesArm32SplitOn32BitDevices() {
        assertEquals(
            tvArm32,
            selectReleaseApkAssetName(
                assetNames = listOf(tvUniversal, tvArm32, tvArm64),
                isTv = true,
                supportedAbis = listOf("armeabi-v7a"),
            ),
        )
    }

    @Test
    fun tvFallsBackToUniversalForOlderReleases() {
        assertEquals(
            tvUniversal,
            selectReleaseApkAssetName(
                assetNames = listOf(tvUniversal, mobile),
                isTv = true,
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun mobileContinuesToUseMobileUniversalApk() {
        assertEquals(
            mobile,
            selectReleaseApkAssetName(
                assetNames = listOf(tvArm64, mobile, tvUniversal),
                isTv = false,
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun tvDoesNotAccidentallySelectMobileWhenNoTvAssetExists() {
        assertNull(
            selectReleaseApkAssetName(
                assetNames = listOf(mobile),
                isTv = true,
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }
}
