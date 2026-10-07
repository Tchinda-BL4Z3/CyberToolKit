package com.example.ui.shell

import com.example.ui.lab.LabNetwork
import com.example.ui.lab.LabTools

/**
 * A small, fully sandboxed command interpreter.
 *
 * ### Why this is not a real shell
 *
 * An Android application cannot run shell commands. There is no `Runtime.exec`
 * on Android, the app sandbox exposes no shell binary, and reaching one requires
 * a rooted device - which a Play-distributed app cannot ask for. Anything that
 * claimed to run `nmap` or `nc` from inside the app would be faking it.
 *
 * So this is an *interpreter*, not a shell: it recognises a fixed set of
 * built-in commands, operates on an in-memory virtual filesystem and answers from
 * values the app already knows. It cannot open a socket, spawn a process, or
 * touch a file outside [VirtualFileSystem], because there is no code path that
 * would do so.
 *
 * The point is pedagogy: the operator types real shell syntax, sees real output,
 * and builds the habit of reading a prompt and parsing an exit status - the same
 * reflexes a real terminal trains, in an environment that cannot be pointed at
 * someone else's machine.
 *
 * It also gives the cheat sheets somewhere to *try* a flag before running it on
 * a lab host, which is the honest use of a tool like this.
 */
object LocalShell {

  /**
   * Virtual home directory, as reported by `pwd`, and the root of the sandbox.
   *
   * The literal lives here rather than in [VirtualFileSystem] so both sides can
   * name it without a circular reference: the shell's parser needs the root when
   * clamping `..`, and the filesystem needs it when seeding the tree.
   */
  const val HOME: String = "/home/operator"

  /** Where the virtual shell starts. */
  const val ROOT: String = HOME

  /** Longest echoed back in one response, so `cat` on a large file cannot blow up the UI. */
  private const val MAX_OUTPUT_CHARS = 4000

  /** Longest single argument accepted, to keep parsing bounded. */
  private const val MAX_INPUT_CHARS = 2000

  /**
   * Runs one command line and returns what a shell would have printed.
   *
   * Never throws: a malformed line is an error the operator should read, not a
   * crash. The result carries a status so the caller can tint the prompt, and a
   * flag saying whether the command should be appended to the transcript.
   */
  fun execute(line: String, fs: VirtualFileSystem, now: Long): Result {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return Result("", 0, false)
    if (trimmed.length > MAX_INPUT_CHARS) {
      return Result("line too long (max $MAX_INPUT_CHARS characters)", 1, true)
    }

    // `help` and the bare prompt are handled before tokenising so they work even
    // if the rest of the parser is handed something unusual.
    val tokens = tokenise(trimmed) ?: return Result("unterminated quote", 2, true)
    if (tokens.isEmpty()) return Result("", 0, false)

    val command = tokens.first()
    val args = tokens.drop(1)

    // The simulated network tools live behind this single branch. Routing them
    // through the same `when` as the filesystem built-ins would have made the
    // file commands and the network tools look interchangeable, which is exactly
    // the confusion the lab is meant to avoid.
    labCommand(command, args)?.let { return it }

    return when (command) {
      "help" -> help()
      "pwd" -> pwd(fs)
      "whoami" -> whoami()
      "date" -> date(now)
      "echo" -> echo(args)
      "cd" -> cd(fs, args)
      "ls" -> ls(fs, args)
      "cat" -> cat(fs, args)
      "type" -> type(args, fs)
      "clear", "cls" -> Result("", 0, true, clearScreen = true)
      else -> Result(
        "${command}: command not found. Type 'help' for the built-in list.",
        127,
        true
      )
    }
  }

  /** Outcome of one command line. */
  data class Result(
    val output: String,
    val status: Int,
    val echo: Boolean,
    val clearScreen: Boolean = false
  ) {
    val failed: Boolean get() = status != 0
  }

  /**
   * Splits on whitespace while honouring single and double quotes, the way a
   * POSIX shell does for the cases that matter here.
   *
   * Returns null on an unterminated quote so the caller can report it rather than
   * silently guessing where the argument ended.
   */
  private fun tokenise(input: String): List<String>? {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false

    for (char in input) {
      when {
        quote != null && char == quote -> quote = null
        quote != null -> current.append(char)
        char == '\'' || char == '"' -> {
          quote = char
          started = true
        }
        char.isWhitespace() -> {
          if (current.isNotEmpty() || started) tokens.add(current.toString())
          current.clear()
          started = false
        }
        else -> current.append(char)
      }
    }
    if (quote != null) return null
    if (current.isNotEmpty() || started) tokens.add(current.toString())
    return tokens
  }

  private fun help(): Result {
    val local = listOf(
      "pwd                 print the working directory",
      "ls [path]           list a directory",
      "cd <path>           change directory ( .. and ~ supported )",
      "cat <file>          print a file",
      "type <command>      show how a command name is resolved",
      "echo <text>         print text",
      "date                current date and time",
      "whoami              current user",
      "clear               clear the screen"
    )
    val simulated = listOf(
      "ping <host>         simulated ICMP echo",
      "nmap [-sV] [-p-] [-sn] <target>",
      "tcpdump [-c N] <host>"
    )
    return Result(
      "Local lab shell.\n" +
        "No process is spawned. File commands read a virtual filesystem;\n" +
        "the network commands below are SIMULATED - they answer from an\n" +
        "invented inventory and never touch a network.\n\n" +
        "Filesystem:\n" + local.joinToString("\n") + "\n\n" +
        "Simulated network:\n" + simulated.joinToString("\n") + "\n\n" +
        "Lab range: ${LabNetwork.SUBNET}.${LabNetwork.HOST_MIN}-${LabNetwork.HOST_MAX}",
      0,
      true
    )
  }

  private fun pwd(fs: VirtualFileSystem): Result = Result(fs.current(), 0, true)

  /**
   * The sandbox user.
   *
   * Deliberately not `root`: this shell has no privilege to escalate to, and
   * saying `root` next to a `whoami` would teach a false mental model.
   */
  private fun whoami(): Result = Result("operator", 0, true)

  private fun date(now: Long): Result {
    val format = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US)
    return Result(format.format(java.util.Date(now)), 0, true)
  }

  private fun echo(args: List<String>): Result =
    Result(args.joinToString(" "), 0, true)

  private fun type(args: List<String>, fs: VirtualFileSystem): Result {
    if (args.isEmpty()) return Result("type: missing operand", 1, true)
    val out = args.map { name ->
      when {
        isBuiltIn(name) -> "$name: shell built-in command"
        // Resolved before asked about: `type notes/recon.txt` should report the
        // file, not a "not found" that only reflects the raw argument.
        fs.resolve(name)?.let { fs.exists(it) } == true -> "$name: file"
        else -> "$name: not found"
      }
    }
    return Result(out.joinToString("\n"), 0, true)
  }

  private fun isBuiltIn(name: String): Boolean =
    name in setOf(
      "pwd", "ls", "cd", "cat", "type", "echo", "date", "whoami", "clear", "help", "cls",
      // Simulated network tools - built-ins in the sense that they run here, but
      // they answer from the invented inventory, never from a real host.
      "ping", "nmap", "tcpdump"
    )

  /**
   * The simulated network commands, or null when [command] is not one of them.
   *
   * Split out so the dispatch above stays readable and so it is obvious that these
   * three - and only these three - reach outside the filesystem built-ins. See
   * [LabTools] for why they invent their answers instead of making any.
   */
  private fun labCommand(command: String, args: List<String>): Result? {
    return when (command) {
      "ping" -> {
        val target = args.firstOrNull()
        if (target == null) {
          Result("ping: usage: ping <host in ${LabNetwork.SUBNET}.x>", 1, true)
        } else {
          Result(LabTools.ping(target), 0, true)
        }
      }
      "nmap" -> {
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) {
          Result("nmap: usage: nmap [-sV] [-p-] [-sn] <target>", 1, true)
        } else {
          val flags = args.filter { it.startsWith("-") }
          Result(LabTools.nmap(targets.first(), flags), 0, true)
        }
      }
      "tcpdump" -> {
        val flags = args.filter { it.startsWith("-") }
        val rest = args.filter { !it.startsWith("-") }
        val count = flags.firstOrNull { it.startsWith("-c") }
          ?.removePrefix("-c")
          ?.toIntOrNull()
          ?: 2
        val filter = rest.joinToString(" ").ifEmpty { "any" }
        Result(LabTools.tcpdump(filter, count), 0, true)
      }
      else -> null
    }
  }

  private fun cd(fs: VirtualFileSystem, args: List<String>): Result {
    if (args.isEmpty()) {
      fs.chdir(HOME)
      return Result(HOME, 0, true)
    }
    return when (val target = fs.resolve(args.first())) {
      null -> Result("cd: no such file or directory: ${args.first()}", 1, true)
      else -> {
        if (!fs.isDirectory(target)) {
          Result("cd: not a directory: ${args.first()}", 1, true)
        } else {
          fs.chdir(target)
          Result("", 0, true)
        }
      }
    }
  }

  private fun ls(fs: VirtualFileSystem, args: List<String>): Result {
    val flags = args.filter { it.startsWith("-") }
    val paths = args.filter { !it.startsWith("-") }
    val target = paths.firstOrNull() ?: fs.current()

    val resolved = fs.resolve(target)
    if (resolved == null || !fs.exists(resolved)) {
      return Result("ls: no such file or directory: $target", 1, true)
    }
    if (!fs.isDirectory(resolved)) {
      return Result(fs.read(resolved) ?: "", 0, true)
    }

    val entries = fs.list(resolved)
    if (entries.isEmpty()) return Result("", 0, true)
    val detailed = flags.contains("-l")
    return Result(
      entries.joinToString("\n") { entry ->
        if (detailed) {
          val size = fs.read(fs.join(resolved, entry))?.length ?: 0
          val kind = if (fs.isDirectory(fs.join(resolved, entry))) "d" else "-"
          "$kind rw-r--r-- ${size.toString().padStart(6)}  $entry"
        } else {
          entry
        }
      },
      0,
      true
    )
  }

  private fun cat(fs: VirtualFileSystem, args: List<String>): Result {
    if (args.isEmpty()) return Result("cat: missing operand", 1, true)
    val out = StringBuilder()
    // Tracked separately from the output text: a `cat` that only printed errors
    // produced text, so inferring success from `out.isNotEmpty()` would report a
    // missing file as a success.
    var missing = false
    for (path in args) {
      val resolved = fs.resolve(path)
      // `resolve` normalises a path, it does not vouch for it existing, so
      // absence is checked here. Without this a missing file would print
      // nothing and still exit 0, which is worse than failing loudly.
      if (resolved == null || !fs.exists(resolved)) {
        out.append("cat: no such file or directory: $path\n")
        missing = true
        continue
      }
      if (fs.isDirectory(resolved)) {
        out.append("cat: is a directory: $path\n")
        missing = true
        continue
      }
      val content = fs.read(resolved) ?: ""
      out.append(content)
      if (!content.endsWith("\n")) out.append("\n")
    }
    val text = out.toString()
    return Result(
      if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n... truncated" else text,
      // Any unreadable operand makes the whole invocation fail, the way `cat`
      // does: the operator asked for N files and did not get all N.
      if (missing) 1 else 0,
      true
    )
  }
}
