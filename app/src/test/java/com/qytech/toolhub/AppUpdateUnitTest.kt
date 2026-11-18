package com.qytech.toolhub

import com.qytech.toolhub.data.AppUpdateRepository
import com.qytech.toolhub.data.GitHubAssetDto
import com.qytech.toolhub.data.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateUnitTest {

    @Test
    fun compareVersions_handlesSemanticAndPrefix() {
        assertTrue(AppUpdateRepository.compareVersions("v0.0.3", "0.0.2") > 0)
        assertTrue(AppUpdateRepository.compareVersions("0.0.10", "0.0.2") > 0)
        assertTrue(AppUpdateRepository.compareVersions("1.0.0", "0.9.9") > 0)
        assertEquals(0, AppUpdateRepository.compareVersions("v0.0.2", "0.0.2"))
        assertTrue(AppUpdateRepository.compareVersions("0.0.1", "0.0.2") < 0)
    }

    @Test
    fun parseReleaseDto_picksApkAssetAndStripsPrefix() {
        val dto = GitHubReleaseDto(
            tagName = "v0.0.3",
            name = "Release v0.0.3",
            body = "Bug fixes",
            htmlUrl = "https://github.com/Jax-Lifu/ToolHub/releases/tag/v0.0.3",
            assets = listOf(
                GitHubAssetDto(name = "mapping.txt", size = 100L, browserDownloadUrl = "https://example.com/mapping.txt"),
                GitHubAssetDto(name = "toolhub-release.apk", size = 2048000L, browserDownloadUrl = "https://example.com/toolhub-release.apk"),
            ),
        )
        val info = AppUpdateRepository.parseReleaseDto(dto)
        assertEquals("0.0.3", info.versionName)
        assertEquals("toolhub-release.apk", info.apkName)
        assertEquals("https://example.com/toolhub-release.apk", info.apkUrl)
        assertEquals(2048000L, info.apkSizeBytes)
    }
}
