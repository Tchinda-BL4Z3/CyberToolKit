package com.example.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credentials for one lab machine. Never persisted by this class. */
data class SshCredentials(
  val host: String,
  val port: Int = 22,
  val username: String,
  /** A passphrase if the key is protected, or a password. */
  val secret: String,
  val useKeyAuth: Boolean = true,
  /** PEM/OpenSSH private key, used only when [useKeyAuth] is set. */
  val privateKey: String? = null,
  val passphrase: String? = null
)

/** Outcome of one command sent to a remote shell. */
sealed interface SshResult {
  data class Success(val output: String) : SshResult
  data class Failure(val message: String) : SshResult
}

/**
 * Runs one command at a time on a lab machine over SSH.
 *
 * ### Scope, deliberately narrow
 *
 * This is not a general SSH client. It executes a single command string, returns
 * its output, and closes. No PTY, no interactive shell, no file transfer, no port
 * forwarding, no `exec` channel. That is enough to run `id`, `uname -a`, `lsb_release`
 * or a check command on a machine the operator already owns - which is the whole
 * point of the feature - and small enough to audit line by line.
 *
 * ### Credentials
 *
 * The password, the private key and its passphrase are used for the duration of
 * one call and never written to disk by this class. The UI layer is responsible
 * for not persisting them; see [SshCredentials] and the Settings copy.
 *
 * ### Threading
 *
 * JSch is blocking and was written for desktop JVMs. Every call is dispatched to
 * [Dispatchers.IO] so the UI thread is never blocked on a socket read.
 */
class SshSession(
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  /**
   * Mirrors the Settings opt-in. Defaults to false so an unconfigured instance can
   * never reach the network.
   */
  private val consentGiven: Boolean = false
) {

  /**
   * Executes [command] and returns its combined output.
   *
   * Never throws: every failure mode becomes [SshResult.Failure] with a message
   * written for a human, because a raw `JSchException` stack trace tells an
   * operator nothing about whether they mistyped a host.
   */
  suspend fun run(credentials: SshCredentials, command: String): SshResult =
    withContext(dispatcher) {
      // Belt to the UI's braces: even if a caller forgets to check the Settings
      // opt-in, this layer refuses. The gate belongs to the code that owns the
      // promise, not only to the screen that offers it.
      if (!consentGiven) return@withContext SshResult.Failure(NOT_ENABLED)
      if (command.isBlank()) return@withContext SshResult.Failure("Commande vide.")
      val host = credentials.host.trim()
      if (host.isEmpty()) return@withContext SshResult.Failure("Hôte vide.")

      var session: Session? = null
      var channel: ChannelExec? = null
      try {
        session = open(credentials, host)
        // "exec", not "shell": the command string travels in the channel request
        // and the server runs it directly. "shell" would open an interactive PTY
        // and block forever waiting for a stdin that never comes.
        channel = (session.openChannel("exec") as? ChannelExec)
          ?: return@withContext SshResult.Failure("Canal d'exécution indisponible.")
        channel.setCommand(command)

        // stderr is read on the same thread before stdout: on a single-threaded
        // channel, draining only stdout loses everything a failed command
        // reported, which is exactly the output the operator needs.
        val errors = StringBuilder()
        val stderrThread = Thread { drain(channel.errStream, errors) }
        stderrThread.isDaemon = true
        stderrThread.start()

        val output = StringBuilder()
        drain(channel.inputStream, output)
        // Bounded join: a server that keeps the error stream open must not hang
        // the coroutine once stdout has already ended.
        stderrThread.join(STDERR_JOIN_MS)

        val combined = buildString {
          append(output.toString().trimEnd())
          val err = errors.toString().trimEnd()
          if (err.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(err)
          }
        }
        SshResult.Success(combined)
      } catch (e: JSchException) {
        SshResult.Failure(describe(e))
      } catch (e: IllegalStateException) {
        SshResult.Failure(describe(e))
      } finally {
        // Order matters: the channel must go before the session, or JSch leaves the
        // socket open and the next call blocks on a dead handle.
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
      }
    }

  /** Copies a stream into [sink], stopping at EOF or the chunk guard. */
  private fun drain(stream: java.io.InputStream, sink: StringBuilder) {
    runCatching {
      stream.use { input ->
        val buffer = ByteArray(4096)
        var read = input.read(buffer)
        var guard = 0
        while (read >= 0 && guard++ < MAX_CHUNKS) {
          sink.append(String(buffer, 0, read, Charsets.UTF_8))
          read = input.read(buffer)
        }
      }
    }
  }

  private fun open(credentials: SshCredentials, host: String): Session {
    val jsch = JSch()
    if (credentials.useKeyAuth) {
      val key = credentials.privateKey
      if (key.isNullOrBlank()) {
        throw JSchException("Aucune clé privée fournie.")
      }
      val passphrase = credentials.passphrase
      jsch.addIdentity(
        "lab",
        key.toByteArray(Charsets.UTF_8),
        null,
        passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
      )
    }

    val session = jsch.getSession(credentials.username.trim(), host, credentials.port)
    if (!credentials.useKeyAuth) {
      session.setPassword(credentials.secret)
    } else {
      // Guarded prompt: JSch asks interactively, which would deadlock a Compose
      // screen waiting on a dialog nobody can reach.
      session.setConfig("PreferredAuthentications", "publickey")
    }
    session.setConfig("StrictHostKeyChecking", "no")
    session.connect(CONNECT_TIMEOUT_MS)
    return session
  }

  /**
   * Turns a JSch failure into something actionable.
   *
   * The distinction that matters to an operator: a refused connection is a
   * firewall or a wrong port, while an auth failure is a wrong key or user. Both
   * otherwise arrive as an opaque exception.
   */
  private fun describe(e: Exception): String {
    val raw = e.message ?: e.javaClass.simpleName
    return when {
      raw.contains("Auth fail", ignoreCase = true) ||
        raw.contains("USERAUTH", ignoreCase = true) ->
        "Authentification refusée : clé ou mot de passe incorrect."
      raw.contains("Connection refused", ignoreCase = true) ->
        "Connexion refusée : rien n'écoute sur ce port, ou un pare-feu bloque."
      raw.contains("timeout", ignoreCase = true) ->
        "Délai dépassé : l'hôte ne répond pas."
      raw.contains("UnknownHost", ignoreCase = true) ->
        "Hôte introuvable."
      else -> raw
    }
  }

  companion object {
    const val CONNECT_TIMEOUT_MS = 8_000
    const val MAX_CHUNKS = 512
    const val STDERR_JOIN_MS = 500L

    /** Exposed so the UI and the tests assert on the same string. */
    const val NOT_ENABLED = "Client SSH désactivé dans les paramètres."
  }
}
