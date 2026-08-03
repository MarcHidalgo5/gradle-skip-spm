package com.theleftbit.skipspm

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The self-heal deletes `.build/plugins/outputs`, whose skipstone tree symlinks back into the
 * package's real `Sources/` and `.build/checkouts` — following those links once destroyed a whole
 * working tree, so the delete must remove symlinks as links, never descend through them.
 */
class DeleteRecursivelyNoFollowLinksTest {

    private val tempDir = createTempDirectory("skipspm-delete-test").toFile()

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `deletes plain files and directories`() {
        val root = File(tempDir, "outputs")
        val nested = File(root, "a/b/c.txt")
        nested.parentFile.mkdirs()
        nested.writeText("x")

        deleteRecursivelyNoFollowLinks(root)

        assertFalse(root.exists())
    }

    @Test
    fun `removes a directory symlink without deleting its target's contents`() {
        val sources = File(tempDir, "Sources")
        val realFile = File(sources, "Feature/Feature.swift")
        realFile.parentFile.mkdirs()
        realFile.writeText("// real source")

        val root = File(tempDir, "outputs")
        File(root, "skipstone").mkdirs()
        val link = File(root, "skipstone/swift")
        Files.createSymbolicLink(link.toPath(), sources.toPath())

        deleteRecursivelyNoFollowLinks(root)

        assertFalse(root.exists(), "the outputs tree should be gone")
        assertTrue(realFile.isFile, "the symlink target's contents must survive")
    }

    @Test
    fun `removes a broken symlink`() {
        val root = File(tempDir, "outputs")
        root.mkdirs()
        val link = File(root, "dangling")
        Files.createSymbolicLink(link.toPath(), File(tempDir, "no-such-target").toPath())

        deleteRecursivelyNoFollowLinks(root)

        assertFalse(root.exists())
    }

    @Test
    fun `missing root is a no-op`() {
        deleteRecursivelyNoFollowLinks(File(tempDir, "never-created"))
    }
}
