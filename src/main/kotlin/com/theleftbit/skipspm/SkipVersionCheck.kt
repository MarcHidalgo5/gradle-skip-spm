package com.theleftbit.skipspm

/**
 * The skip toolchain version a package declares, extracted from `Package.resolved` /
 * `Package.swift`. When the `skip` CLI (Homebrew) and the pinned `skip` SwiftPM package (the
 * skipstone transpiler — same repo, same version stream) drift apart, exports fail with cryptic,
 * far-away errors (e.g. unresolved bridging symbols in generated Kotlin), so the export preflights
 * the comparison and reports the drift in plain terms.
 */
internal data class SkipVersionRequirement(
    val version: String,
    /** True for a resolved pin or an `exact:` requirement; false for `from:`-style minimums. */
    val exact: Boolean,
) {
    /** A human-readable description of the mismatch with [cliVersion], or null when compatible. */
    fun mismatchWith(cliVersion: String): String? = when {
        exact && compareDottedVersions(cliVersion, version) != 0 ->
            "the skip CLI is $cliVersion but the package pins skip $version " +
                "(Package.swift/Package.resolved). Align them: update the pin to $cliVersion, or " +
                "install skip $version."
        !exact && compareDottedVersions(cliVersion, version) < 0 ->
            "the skip CLI is $cliVersion but the package requires at least skip $version " +
                "(Package.swift). Update the skip CLI."
        else -> null
    }
}

/**
 * The skip version the package declares. The `Package.resolved` pin wins when present (it is what
 * the export actually builds with); otherwise the `Package.swift` requirement on `skip.git` is
 * used. Returns null when neither declares one (e.g. polymarket-style packages whose skip
 * dependencies are SKIP_ENABLED-gated and stripped from the committed lockfile) — the check is
 * then skipped entirely.
 */
internal fun expectedSkipVersion(packageSwift: String?, packageResolved: String?): SkipVersionRequirement? {
    packageResolved?.let { resolved ->
        // Package.resolved v2/v3 pins are key-sorted objects, so skip's own "version" is the first
        // one after its "identity". `"skip"` is exact-quoted, so skip-bridge/skip-fuse don't match.
        RESOLVED_SKIP_PIN.find(resolved)?.let { return SkipVersionRequirement(it.groupValues[1], exact = true) }
    }
    packageSwift?.let { manifest ->
        MANIFEST_SKIP_REQUIREMENT.find(manifest)?.let { match ->
            val (label, labeledVersion, rangeVersion) = match.destructured
            return if (label == "exact") {
                SkipVersionRequirement(labeledVersion, exact = true)
            } else {
                SkipVersionRequirement(labeledVersion.ifEmpty { rangeVersion }, exact = false)
            }
        }
    }
    return null
}

/** First dotted version in `skip version` output (`Skip version 1.9.4` → `1.9.4`), or null. */
internal fun parseSkipCliVersion(output: String): String? =
    Regex("""(\d+(?:\.\d+)+)""").find(output)?.groupValues?.get(1)

/** Numeric segment-wise comparison; missing segments count as 0 (`1.9` == `1.9.0`). */
internal fun compareDottedVersions(a: String, b: String): Int {
    val aParts = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val bParts = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    repeat(maxOf(aParts.size, bParts.size)) { i ->
        val diff = aParts.getOrElse(i) { 0 } - bParts.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}

private val RESOLVED_SKIP_PIN =
    Regex(""""identity"\s*:\s*"skip"[\s\S]*?"version"\s*:\s*"([^"]+)"""")

/** Matches `…/skip.git", exact: "1.9.3"`, `…, from: "1.9.3"`, and `…, .upToNextMajor(from: "1.9.3")`. */
private val MANIFEST_SKIP_REQUIREMENT = Regex(
    """"[^"]*/skip\.git"\s*,\s*(?:(exact|from)\s*:\s*"([^"]+)"|\.upToNext(?:Major|Minor)\s*\(\s*from:\s*"([^"]+)"\s*\))""",
)
