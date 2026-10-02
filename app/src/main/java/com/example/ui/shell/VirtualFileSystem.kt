package com.example.ui.shell

/**
 * The in-memory filesystem the lab shell reads from.
 *
 * Every path is confined to [LocalShell.ROOT] and resolved textually: `..` climbs up and
 * stops at the root rather than escaping it, so `cd ../../../../etc` lands back
 * at [LocalShell.ROOT] instead of reaching anywhere real. There is no bridge from this class
 * to the device filesystem, by design - see [LocalShell] for why that matters.
 */
class VirtualFileSystem(
  files: Map<String, String> = DEFAULT_FILES,
  directories: Set<String> = DEFAULT_DIRECTORIES
) {
  private val files = files.toMutableMap()
  private val directories = directories.toMutableSet()
  private var cwd: String = LocalShell.ROOT

  fun current(): String = cwd

  fun chdir(path: String) {
    cwd = path
  }

  /** True when [path] is a directory in the sandbox. */
  fun isDirectory(path: String): Boolean = path in directories

  /** Raw file contents, or null when absent. */
  fun read(path: String): String? = files[path]

  fun exists(path: String): Boolean = path in files || path in directories

  /** Entry names directly under [path]; empty when it is not a directory. */
  fun list(path: String): List<String> {
    if (path !in directories) return emptyList()
    val prefix = "$path/"
    val names = linkedSetOf<String>()
    (files.keys + directories).forEach { candidate ->
      if (!candidate.startsWith(prefix)) return@forEach
      val remainder = candidate.removePrefix(prefix)
      if (remainder.isEmpty() || remainder.contains('/')) return@forEach
      names.add(remainder)
    }
    return names.sorted()
  }

  /** Joins a directory and an entry name into an absolute sandbox path. */
  fun join(directory: String, name: String): String =
    if (directory == LocalShell.ROOT) "${LocalShell.ROOT}/$name" else "$directory/$name"

  /**
   * Turns a user-supplied path into an absolute sandbox path, or null when it
   * points outside the sandbox.
   *
   * Handles absolute paths, `~`, `.`, `..` and relative segments. `..` is clamped
   * at [LocalShell.ROOT], so there is no parent to escape to.
   */
  fun resolve(input: String): String? {
    val raw = input.trim()
    if (raw.isEmpty()) return null

    // An absolute path is read from the sandbox root; a relative one from the
    // working directory. `..` then climbs whatever that produced.
    // An absolute path has to sit under the sandbox root. Rewriting `/etc/passwd`
    // to `<root>/etc/passwd` would be safe but confusing - it would answer for a
    // path the operator did not ask about - so anything outside is refused, and
    // the caller reports it as missing.
    if (raw.startsWith("/") &&
      raw != LocalShell.ROOT &&
      !raw.startsWith("${LocalShell.ROOT}/")
    ) {
      return null
    }

    // An absolute path starts from the sandbox root, a relative one from the
    // working directory. The tail is walked exactly once: pre-seeding the base
    // *and* iterating the raw segments would apply the leading components twice
    // and produce `/home/operator/home/operator/...`.
    val absolute = raw.startsWith("/")
    val base = if (absolute) rootSegments() else cwd.trim('/').split('/')

    // For an absolute path the tail is what follows the sandbox root; the base
    // already holds the root. Walking the whole path again would re-append it.
    val tail = when {
      !absolute -> raw
      raw == LocalShell.ROOT -> ""
      else -> raw.removePrefix("${LocalShell.ROOT}/")
    }

    val segments = mutableListOf<String>()
    segments.addAll(base)

    tail.split('/').forEach { segment ->
      when (segment) {
        "", "." -> Unit
        "~" -> {
          segments.clear()
          segments.addAll(rootSegments())
        }
        ".." -> if (segments.size > rootSegments().size) {
          // Clamped at the sandbox root: there is deliberately no parent to
          // reach, so `cd ../../../..` lands back at the root rather than
          // escaping it. This is the guarantee the class comment promises.
          segments.removeAt(segments.size - 1)
        }
        else -> segments.add(segment)
      }
    }

    if (segments.size <= rootSegments().size) return LocalShell.ROOT
    val path = segments.joinToString("/", prefix = "/")
    // The clamp above already prevents this, but an explicit check keeps the
    // guarantee local and obvious rather than dependent on the loop.
    return if (path == LocalShell.ROOT || path.startsWith("${LocalShell.ROOT}/")) path else null
  }

  /** `["home", "operator"]` - the fixed prefix every sandboxed path carries. */
  private fun rootSegments(): List<String> = LocalShell.ROOT.trim('/').split('/')

  companion object {
    /** Seeds a small lab tree: a home directory with notes and a couple of files. */
    val DEFAULT_FILES: Map<String, String> = mapOf(
      "/home/operator/readme.txt" to buildString {
        appendLine("Local lab shell - sandboxed, no real processes.")
        appendLine()
        appendLine("Commands are parsed and answered by the app itself.")
        appendLine("Nothing is spawned, no socket is opened.")
        appendLine()
        appendLine("Try:  ls -l   |   cd notes   |   cat recon.txt   |   type nmap")
      },
      "/home/operator/notes/recon.txt" to buildString {
        appendLine("Recon notes")
        appendLine("============")
        appendLine()
        appendLine("Host: 10.10.14.0/24")
        appendLine("Ports seen: 22, 80, 443")
        appendLine()
        appendLine("Remember: every command typed here stays on this device.")
      },
      "/home/operator/notes/cheatsheet.txt" to buildString {
        appendLine("Command families")
        appendLine("-----------------")
        appendLine()
        appendLine("nmap      - discovery and service detection")
        appendLine("wireshark - traffic analysis")
        appendLine("sqlmap    - SQL injection testing")
        appendLine()
        appendLine("This shell cannot run them. Copy them to a lab host.")
      },
      "/home/operator/bin/toolbox.sh" to buildString {
        appendLine("#!/bin/sh")
        appendLine("# Reference script, stored as text. Not executable here.")
        appendLine("echo \"run this on your lab machine, not on a phone\"")
      }
    )

    val DEFAULT_DIRECTORIES: Set<String> = setOf(
      "/home",
      "/home/operator",
      "/home/operator/notes",
      "/home/operator/bin"
    )
  }
}
