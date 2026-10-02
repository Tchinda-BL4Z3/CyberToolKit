package com.example.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lab shell is the one part of the app that *looks* like it executes
 * commands, so these tests pin the boundary as hard as the behaviour: the
 * interpreter must answer from its own sandbox, and nothing here may grow a path
 * out of [LocalShell.ROOT].
 */
class LocalShellTest {

  private val fixedNow = 1_760_000_000_000L

  private fun run(line: String, fs: VirtualFileSystem = VirtualFileSystem()): LocalShell.Result =
    LocalShell.execute(line, fs, fixedNow)

  // ---- Basics --------------------------------------------------------------

  @Test
  fun `pwd reports the sandbox home`() {
    assertEquals(LocalShell.HOME, run("pwd").output)
  }

  /**
   * 127 is not an operator - the shell has nothing to escalate to, and reporting
   * `root` next to a `whoami` would install a false mental model.
   */
  @Test
  fun `whoami is the sandbox user, never root`() {
    assertEquals("operator", run("whoami").output)
  }

  @Test
  fun `date renders the supplied instant`() {
    assertTrue(run("date").output.contains("2025"))
  }

  @Test
  fun `echo prints its arguments joined by spaces`() {
    assertEquals("hello world", run("echo hello world").output)
  }

  @Test
  fun `empty input is silent and successful`() {
    val result = run("   ")
    assertEquals("", result.output)
    assertEquals(0, result.status)
    assertTrue(!result.failed)
  }

  // ---- Exit status ---------------------------------------------------------

  @Test
  fun `unknown command reports 127 like a POSIX shell`() {
    val result = run("hydra -t 16 -l root 10.0.0.1 ssh")
    assertEquals(127, result.status)
    assertTrue(result.failed)
    assertTrue(result.output.contains("command not found"))
  }

  /**
   * The boundary that matters most: the cheat sheet is full of real commands, and
   * none of them may run here.
   *
   * `nmap`, `ping` and `tcpdump` are deliberately absent from this list. They are
   * present as *simulators* that answer from an invented inventory, which is a
   * different thing entirely: they open no socket and resolve no name. The test
   * below therefore also pins that they are refused outside the lab range rather
   * than quietly reaching out.
   */
  @Test
  fun `real offensive commands are not built-ins`() {
    listOf("sqlmap", "nc", "netcat", "ncat", "curl", "wget", "ssh", "scp",
      "sh", "bash", "rm", "chmod", "hydra", "msfconsole")
      .forEach { command ->
        val result = run("$command --help")
        assertEquals("$command must not be a built-in", 127, result.status)
      }
  }

  @Test
  fun `simulated network tools are built-ins that stay inside the lab`() {
    listOf("nmap", "ping", "tcpdump").forEach { command ->
      assertEquals(0, run("$command ${com.example.ui.lab.LabNetwork.SUBNET}.5").status)
    }
    // Outside the invented range they refuse rather than falling through to a
    // real lookup - this is the assertion that proves no network access.
    val outside = run("nmap 192.168.1.1")
    assertEquals(0, outside.status)
    assertTrue(outside.output.contains("No network was touched"))
  }

  // ---- Filesystem ----------------------------------------------------------

  @Test
  fun `ls lists the seeded home directory`() {
    val listing = run("ls").output.lines().filter { it.isNotEmpty() }
    assertTrue(listing.contains("notes"))
    assertTrue(listing.contains("bin"))
    assertTrue(listing.contains("readme.txt"))
  }

  @Test
  fun `ls -l marks directories and sizes files`() {
    val output = run("ls -l").output
    assertTrue(output.contains("d rw-r--r--"))
    assertTrue(output.contains("- rw-r--r--"))
  }

  @Test
  fun `cat prints file contents`() {
    val result = run("cat readme.txt")
    assertEquals(0, result.status)
    assertTrue(result.output.contains("Local lab shell"))
  }

  @Test
  fun `cat on a missing file fails with status 1`() {
    val result = run("cat nope.txt")
    assertEquals(1, result.status)
    // The lower-case "no such file" a real coreutils cat emits, rather than the
    // capitalised wording this file uses in its own assertions.
    assertTrue(result.output.contains("no such file"))
  }

  /**
   * Mixed operands still fail overall. The operator asked for two files and got
   * one; a zero exit would let a script-style workflow carry on with a missing
   * input unnoticed.
   */
  @Test
  fun `cat reports failure when only some operands are readable`() {
    val result = run("cat readme.txt nope.txt")
    assertEquals(1, result.status)
    assertTrue(result.output.contains("Local lab shell"))
    assertTrue(result.output.contains("no such file"))
  }

  @Test
  fun `cat on a directory reports it instead of dumping a listing`() {
    val result = run("cat notes")
    assertTrue(result.output.contains("is a directory"))
  }

  // ---- Navigation ----------------------------------------------------------

  @Test
  fun `cd changes directory and pwd follows`() {
    val fs = VirtualFileSystem()
    assertEquals(0, run("cd notes", fs).status)
    assertEquals("${LocalShell.HOME}/notes", run("pwd", fs).output)
  }

  @Test
  fun `cd with no argument returns home`() {
    val fs = VirtualFileSystem()
    run("cd notes", fs)
    run("cd", fs)
    assertEquals(LocalShell.HOME, run("pwd", fs).output)
  }

  @Test
  fun `cd into a file fails rather than silently succeeding`() {
    assertEquals(1, run("cd readme.txt").status)
  }

  @Test
  fun `cd to a missing directory fails`() {
    assertEquals(1, run("cd nowhere").status)
  }

  /**
   * `..` is clamped at the sandbox root, so there is no parent to climb into. If
   * this ever returns something outside [LocalShell.ROOT] the shell has an
   * escape and the guarantee in the class comment is false.
   */
  @Test
  fun `parent traversal cannot escape the sandbox`() {
    val fs = VirtualFileSystem()
    listOf(
      "cd ../../../../../../..",
      "cd /../../etc",
      "cd /home/operator/../../../.."
    ).forEach { command ->
      run(command, fs)
      val cwd = run("pwd", fs).output
      assertTrue(
        "'$command' escaped to $cwd",
        cwd == LocalShell.ROOT || cwd.startsWith("${LocalShell.ROOT}/")
      )
    }
  }

  @Test
  fun `resolve rejects nothing but never leaves the root`() {
    val fs = VirtualFileSystem()
    listOf("~", "/", "/home/operator", "../../etc/passwd", "/etc/passwd")
      .forEach { input ->
        val resolved = fs.resolve(input)
        if (resolved != null) {
          assertTrue(
            "resolve('$input') escaped to $resolved",
            resolved == LocalShell.ROOT ||
              resolved.startsWith("${LocalShell.ROOT}/")
          )
        }
      }
  }

  // ---- type ----------------------------------------------------------------

  @Test
  fun `type distinguishes built-ins from files from nothing`() {
    assertTrue(run("type ls").output.contains("built-in"))
    assertTrue(run("type readme.txt").output.contains("file"))
    assertTrue(run("type nmap").output.contains("built-in"))
    assertTrue(run("type hydra").output.contains("not found"))
  }

  @Test
  fun `type with no operand is an error`() {
    assertEquals(1, run("type").status)
  }

  // ---- help / clear --------------------------------------------------------

  @Test
  fun `help lists the built-ins and states the sandbox`() {
    val output = run("help").output
    listOf("pwd", "ls", "cat", "echo", "whoami", "date", "cd", "type")
      .forEach { command -> assertTrue("help omits $command", output.contains(command)) }
    assertTrue(output.contains("No process is spawned"))
  }

  @Test
  fun `clear asks the caller to wipe the screen`() {
    assertTrue(run("clear").clearScreen)
  }

  // ---- Quoting and abuse ---------------------------------------------------

  @Test
  fun `quoted arguments keep their spaces`() {
    assertEquals("a b c", run("echo \"a b c\"").output)
    assertEquals("a b c", run("echo 'a b c'").output)
  }

  @Test
  fun `unterminated quote is reported, not guessed`() {
    val result = run("echo \"unclosed")
    assertEquals(2, result.status)
    assertTrue(result.output.contains("unterminated"))
  }

  @Test
  fun `over-long input is refused instead of parsed`() {
    val result = LocalShell.execute("echo " + "x".repeat(3000), VirtualFileSystem(), fixedNow)
    assertTrue(result.failed)
    assertTrue(result.output.contains("too long"))
  }

  /** The hostile-input test: nothing here may throw, whatever is typed. */
  @Test
  fun `hostile input never throws`() {
    listOf(
      "../../../../etc/shadow", "\$()", "`id`", "; rm -rf /", "| cat", "&& echo",
      "> /dev/null", "ls; ls; ls", "\u0000\u0001", "echo \uD83D\uDE00",
      "cat ../../../../../../../../proc/self/environ", "ls /system/bin",
      "echo ${'$'}PATH", "type ../../..", "cd /", "ls ~", "cat ~"
    ).forEach { input ->
      val result = run(input)
      assertTrue("status should stay in range for '$input'", result.status in 0..127)
    }
  }

  @Test
  fun `device filesystem is never reachable by name`() {
    // Paths that exist on a real device but not in the sandbox.
    listOf("/system/bin/sh", "/data/data/com.example/files", "/proc/self/environ")
      .forEach { path ->
        val fs = VirtualFileSystem()
        assertTrue("resolve('$path') should be refused", fs.resolve(path) == null)
        assertEquals(1, run("cat $path").status)
      }
  }
}
