package com.theleftbit.skipspm

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives a real Gradle build of [SkipExportTask] with a *fake* `skip` executable (injected via the
 * task's `SKIP_PATH` override), so the stale-outputs self-heal loop is exercised end to end —
 * failure classification, `.build/plugins/outputs` cleanup, retry, and the give-up path — without
 * a Swift toolchain.
 */
class SkipExportTaskFunctionalTest {

    private val projectDir = createTempDirectory("skipspm-functional").toFile()
    private val pkgDir = File(projectDir, "pkg")
    private val transpilerOutputs = File(pkgDir, TRANSPILER_OUTPUTS_PATH)
    private val fixturesDir = File(projectDir, "fixtures")
    private val invocationMarker = File(projectDir, "skip-invoked-once")

    /** Verbatim shape of the SwiftPM failure seen after the transpiler outputs went stale. */
    private val staleManifestError =
        "error: 'skip-keychain': the package manifest at " +
            "'/repo/.build/plugins/outputs/pkg/USLive/destination/skipstone/USLive/src/main/swift/" +
            "Packages/skip-keychain/Package.swift' cannot be accessed (doesn't exist in file system)"

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `self-heals stale transpiler outputs and re-exports`() {
        // Fake skip: fails with the stale-manifest signature on the first call, succeeds on the second.
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip(
            """
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
              touch "${invocationMarker.absolutePath}"
              echo "$staleManifestError" >&2
              exit 1
            fi
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )
        val staleJunk = File(transpilerOutputs, "pkg/USLive/destination/skipstone/junk.txt")
        staleJunk.parentFile.mkdirs()
        staleJunk.writeText("stale")

        val gradleRunner = runner(fakeSkip)
        // skipstone's outputs symlink back into the package's real Sources; the self-heal must
        // delete the link itself, never the sources behind it.
        val sourcesLink = File(staleJunk.parentFile, "swift-sources")
        java.nio.file.Files.createSymbolicLink(sourcesLink.toPath(), File(pkgDir, "Sources").toPath())
        val realSource = File(pkgDir, "Sources/placeholder.swift")

        val result = gradleRunner.build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "stale transpiler outputs")
        assertFalse(staleJunk.exists(), "self-heal should have deleted the transpiler outputs")
        assertTrue(realSource.isFile, "self-heal must not follow symlinks into the real Sources")
        val aar = File(projectDir, "lib/debug/TestModule-debug.aar")
        assertTrue(aar.isFile, "the retried export should have produced the AAR")
        assertContains(readManifest(aar), "package=\"com.test.shared.testmodule\"")
    }

    @Test
    fun `husk AAR triggers the same self-heal`() {
        // Fake skip: succeeds both times, but the first export packages a husk (no compiled classes).
        val huskDir = File(projectDir, "fixtures-husk").apply { mkdirs() }
        writeAar(huskDir, "TestModule-debug.aar", emptyList())
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip(
            """
            if [ ! -f "${invocationMarker.absolutePath}" ]; then
              touch "${invocationMarker.absolutePath}"
              cp "${huskDir.absolutePath}"/*.aar "${'$'}out"/
              exit 0
            fi
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )
        transpilerOutputs.mkdirs()

        val result = runner(fakeSkip).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "husk AARs")
        assertTrue(aarHasCompiledClasses(File(projectDir, "lib/debug/TestModule-debug.aar")))
    }

    @Test
    fun `gives up after one self-heal attempt`() {
        val fakeSkip = writeFakeSkip(
            """
            echo "$staleManifestError" >&2
            exit 1
            """,
        )
        transpilerOutputs.mkdirs()

        val result = runner(fakeSkip).buildAndFail()

        assertContains(result.output, "a clean re-export did not recover")
        assertContains(result.output, "cleanSharedBuild")
    }

    @Test
    fun `leads skip children's PATH with the configured gradle and disables their build cache`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val envDump = File(projectDir, "skip-env")
        val fakeSkip = writeFakeSkip(
            """
            printenv PATH > "${envDump.absolutePath}"
            printenv GRADLE_OPTS >> "${envDump.absolutePath}" || true
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )

        val result = runner(
            fakeSkip,
            extraTaskConfig = """gradleInstallBinDir.set("/fake/gradle-dist/bin")""",
            extraEnv = mapOf("GRADLE_OPTS" to "-Xmx1g"),
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        val (path, opts) = envDump.readLines().let { it[0] to it.getOrElse(1) { "" } }
        assertTrue(
            path.startsWith("/fake/gradle-dist/bin:"),
            "children's PATH should lead with the configured gradle bin dir, was: $path",
        )
        assertContains(opts, "-Xmx1g", message = "inherited GRADLE_OPTS must be preserved")
        assertContains(opts, "-Dorg.gradle.caching=false")
    }

    @Test
    fun `childGradleBuildCache=true leaves the children's build cache alone`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val envDump = File(projectDir, "skip-env")
        val fakeSkip = writeFakeSkip(
            """
            printenv GRADLE_OPTS > "${envDump.absolutePath}" || true
            cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/
            """,
        )

        val result = runner(
            fakeSkip,
            extraTaskConfig = """childGradleBuildCache.set(true)""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        val opts = envDump.readLines().firstOrNull().orEmpty()
        assertFalse(
            opts.contains("org.gradle.caching=false"),
            "opt-in must not inject the caching override, was: $opts",
        )
    }

    @Test
    fun `warns by default when the skip CLI drifts from the manifest pin`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")

        // Fake CLI reports 1.9.3; the manifest pins 9.9.9 exactly.
        val result = runner(
            fakeSkip,
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "9.9.9"),""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertContains(result.output, "the skip CLI is 1.9.3 but the package pins skip 9.9.9")
    }

    @Test
    fun `fails on drift when skipVersionCheck is fail`() {
        val fakeSkip = writeFakeSkip("exit 0")

        val result = runner(
            fakeSkip,
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "9.9.9"),""",
            extraTaskConfig = """skipVersionCheck.set("fail")""",
        ).buildAndFail()

        assertContains(result.output, "the skip CLI is 1.9.3 but the package pins skip 9.9.9")
    }

    @Test
    fun `matching cli version stays silent`() {
        writeAar(fixturesDir, "TestModule-debug.aar", listOf("com/test/Foo.class"))
        val fakeSkip = writeFakeSkip("""cp "${fixturesDir.absolutePath}"/*.aar "${'$'}out"/""")

        val result = runner(
            fakeSkip,
            packageSwift = """.package(url: "https://source.skip.tools/skip.git", exact: "1.9.3"),""",
            extraTaskConfig = """skipVersionCheck.set("fail")""",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTest")?.outcome)
        assertFalse(result.output.contains("skip CLI"), "no drift message expected")
    }

    private fun runner(
        fakeSkip: File,
        packageSwift: String = "// swift-tools-version:5.9",
        extraTaskConfig: String = "",
        extraEnv: Map<String, String> = emptyMap(),
    ): GradleRunner {
        File(pkgDir, "Sources").mkdirs()
        File(pkgDir, "Sources/placeholder.swift").writeText("// swift source")
        File(pkgDir, "Package.swift").writeText(packageSwift)
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"export-test\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("com.theleftbit.skipspm") apply false }

            tasks.register("exportTest", com.theleftbit.skipspm.SkipExportTask::class.java) {
                packageDir.set(layout.projectDirectory.dir("pkg"))
                sources.set(layout.projectDirectory.dir("pkg/Sources"))
                manifests.from(layout.projectDirectory.file("pkg/Package.swift"))
                module.set("TestModule")
                buildMode.set("debug")
                abis.set(listOf("arm64-v8a"))
                namespacePrefix.set("com.test.shared")
                outputDir.set(layout.projectDirectory.dir("lib/debug"))
                $extraTaskConfig
            }
            """.trimIndent(),
        )
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("exportTest")
            .withEnvironment(System.getenv() + extraEnv + ("SKIP_PATH" to fakeSkip.absolutePath))
    }

    /** A fake `skip` CLI: parses `-d <out>` like the real one, then runs [body]. */
    private fun writeFakeSkip(body: String): File {
        val script = File(projectDir, "fake-skip")
        script.writeText(
            buildString {
                appendLine("#!/bin/bash")
                appendLine("if [ \"\$1\" = \"version\" ]; then echo \"Skip version 1.9.3\"; exit 0; fi")
                appendLine("out=\"\"")
                appendLine("prev=\"\"")
                appendLine("for a in \"\$@\"; do")
                appendLine("  if [ \"\$prev\" = \"-d\" ]; then out=\"\$a\"; fi")
                appendLine("  prev=\"\$a\"")
                appendLine("done")
                appendLine(body.trimIndent())
            },
        )
        script.setExecutable(true)
        return script
    }

    private fun readManifest(aar: File): String =
        ZipFile(aar).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes().decodeToString()
        }

    /** Writes `<name>` into [dir]: an AAR whose classes.jar holds [classEntries]. */
    private fun writeAar(dir: File, name: String, classEntries: List<String>) {
        dir.mkdirs()
        val aar = File(dir, name)
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"skip.placeholder\"/>".toByteArray())
            zip.closeEntry()
            val jarBytes = ByteArrayOutputStream().also { buf ->
                ZipOutputStream(buf).use { jar ->
                    classEntries.forEach { entry ->
                        jar.putNextEntry(ZipEntry(entry))
                        jar.write(byteArrayOf(1, 2, 3))
                        jar.closeEntry()
                    }
                    if (classEntries.isEmpty()) {
                        // An empty ZipOutputStream throws on close; give husks a resource-only entry.
                        jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                        jar.write(byteArrayOf(1))
                        jar.closeEntry()
                    }
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
        }
    }
}
