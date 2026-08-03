package com.theleftbit.skipspm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipVersionCheckTest {

    // Verbatim shape of polymarket-shared's declaration.
    private val exactManifest = """
        .addingSkipDependencies([
          .package(url: "https://source.skip.tools/skip.git", exact: "1.9.3"),
          .package(url: "https://source.skip.tools/skip-fuse.git", exact: "1.0.2"),
        ])
    """.trimIndent()

    private val resolvedWithSkipPin = """
        {
          "pins" : [
            {
              "identity" : "skip-bridge",
              "kind" : "remoteSourceControl",
              "location" : "https://source.skip.tools/skip-bridge.git",
              "state" : { "revision" : "abc", "version" : "0.17.2" }
            },
            {
              "identity" : "skip",
              "kind" : "remoteSourceControl",
              "location" : "https://source.skip.tools/skip.git",
              "state" : { "revision" : "def", "version" : "1.9.3" }
            }
          ],
          "version" : 3
        }
    """.trimIndent()

    @Test
    fun `manifest exact pin is an exact requirement`() {
        val req = expectedSkipVersion(exactManifest, packageResolved = null)
        assertEquals(SkipVersionRequirement("1.9.3", exact = true), req)
    }

    @Test
    fun `manifest from pin is a minimum requirement`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", from: "1.5.0"),""",
            packageResolved = null,
        )
        assertEquals(SkipVersionRequirement("1.5.0", exact = false), req)
    }

    @Test
    fun `manifest upToNextMajor pin is a minimum requirement`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", .upToNextMajor(from: "1.5.13")),""",
            packageResolved = null,
        )
        assertEquals(SkipVersionRequirement("1.5.13", exact = false), req)
    }

    @Test
    fun `resolved pin wins over the manifest and is exact`() {
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip.git", from: "1.5.0"),""",
            resolvedWithSkipPin,
        )
        assertEquals(SkipVersionRequirement("1.9.3", exact = true), req)
    }

    @Test
    fun `sibling skip-dash packages never match`() {
        // Only skip.git / identity "skip" counts; skip-fuse, skip-bridge, … are separate packages.
        val req = expectedSkipVersion(
            """.package(url: "https://source.skip.tools/skip-fuse.git", exact: "1.0.2"),""",
            """{"pins":[{"identity":"skip-bridge","state":{"version":"0.17.2"}}]}""",
        )
        assertNull(req)
    }

    @Test
    fun `no declaration anywhere means no check`() {
        assertNull(expectedSkipVersion("// swift-tools-version:5.9", packageResolved = null))
        assertNull(expectedSkipVersion(null, null))
    }

    @Test
    fun `cli version parses from skip version output`() {
        assertEquals("1.9.4", parseSkipCliVersion("Skip version 1.9.4"))
        assertNull(parseSkipCliVersion("command not found"))
    }

    @Test
    fun `exact requirement flags any drift, either direction`() {
        val req = SkipVersionRequirement("1.9.3", exact = true)
        assertNotNull(req.mismatchWith("1.9.4"))
        assertNotNull(req.mismatchWith("1.8.0"))
        assertNull(req.mismatchWith("1.9.3"))
    }

    @Test
    fun `minimum requirement only flags an older cli`() {
        val req = SkipVersionRequirement("1.9.3", exact = false)
        assertNull(req.mismatchWith("1.9.3"))
        assertNull(req.mismatchWith("1.10.0"))
        assertTrue(req.mismatchWith("1.9.2")!!.contains("at least"))
    }

    @Test
    fun `version comparison is numeric, not lexicographic`() {
        assertTrue(compareDottedVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareDottedVersions("1.9", "1.9.0"))
        assertTrue(compareDottedVersions("0.17.2", "0.17.10") < 0)
    }
}
