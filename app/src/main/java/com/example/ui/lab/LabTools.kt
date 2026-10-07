package com.example.ui.lab

/**
 * Simulated network tools for the lab shell: `ping`, `nmap`, `tcpdump`.
 *
 * ### The boundary
 *
 * Every function here takes a string and returns text. None of them can reach a
 * network: there is no socket, no `InetAddress`, no resolver anywhere in this
 * file, and the app declares no `INTERNET` permission to grant one even if there
 * were. Output is derived exclusively from [LabNetwork], which is invented.
 *
 * That makes these *teaching* tools. They teach the shape of a result - what a
 * ping response looks like, how an nmap report is laid out, which columns matter
 * and how to read a service version - so the operator is not meeting the format
 * for the first time while staring at a real target.
 *
 * They are labelled as simulations everywhere they are surfaced. A tool that
 * looked real and quietly invented its answers would be worse than no tool.
 */
object LabTools {

  /** Longest table `tcpdump` will print before truncating. */
  private const val MAX_PACKET_LINES = 24

  /** Renders a simulated ping against the invented network. */
  fun ping(target: String): String {
    val host = LabNetwork.hostAt(target)
      ?: return unknownHost(target)

    if (!host.up) {
      return "PING $target: no route to host\n" +
        "--- $target ping statistics ---\n" +
        "0 packets transmitted, 0 received, 100% packet loss"
    }

    val latency = LabNetwork.latencyFor(host)
    val lines = (1..4).map { sequence ->
      // Jitter is derived from the sequence number, so the timings look organic
      // while staying identical between two runs.
      val jitter = ((sequence * 37) % 23) / 100.0
      val time = String.format("%.3f", latency + jitter)
      "64 bytes from $target: icmp_seq=$sequence ttl=64 time=$time ms"
    }

    val summary = String.format(
      "--- $target ping statistics ---\n" +
        "4 packets transmitted, 4 received, 0%% packet loss, time 3003ms\n" +
        "rtt min/avg/max/mdev = %.3f/%.3f/%.3f/%.3f ms",
      latency, latency + 0.05, latency + 0.2, 0.05
    )
    return "PING $target: 56 data bytes\n" + lines.joinToString("\n") + "\n" + summary
  }

  /** Renders a simulated nmap scan. Flags understood: `-sV`, `-p-`, `-sn`. */
  fun nmap(target: String, flags: List<String>): String {
    if (flags.contains("-sn")) return hostDiscovery(target)

    val scanPorts = flags.contains("-p-")
    val serviceDetect = flags.contains("-sV")

    // A CIDR sweep, the case that teaches the most about nmap output.
    if (target.contains("/")) {
      val prefix = target.substringBefore('/')
      val hosts = LabNetwork.inventory.filter { it.up }
      return buildString {
        appendLine("Starting Nmap 7.94 (simulated) at ${LabNetwork.SUBNET}.0/24")
        appendLine("Nmap scan report for $prefix.0/24")
        appendLine("Host is up (simulated latency).")
        appendLine()
        hosts.forEach { host ->
          appendLine("Host ${host.id} up:")
          host.services.forEach { service ->
            appendLine("  ${service.port}/tcp open ${service.name}")
          }
          if (host.services.isEmpty()) appendLine("  (no open ports reported)")
        }
        appendLine()
        appendLine("Nmap done: simulated inventory of ${hosts.size} hosts.")
      }
    }

    val host = LabNetwork.hostAt(target) ?: return unknownHost(target)
    if (!host.up) {
      return buildString {
        appendLine("Nmap scan report for $target")
        appendLine("Host $target is down.")
        appendLine("Nmap done: 1 IP address (1 host down) in 0.04 seconds (simulated)")
      }
    }

    return buildString {
      appendLine("Starting Nmap 7.94 (simulated) at ${host.id}")
      appendLine("Nmap scan report for ${host.id}")
      appendLine("Host ${host.id} is up - simulated latency ${String.format("%.1f", LabNetwork.latencyFor(host))} ms.")
      appendLine()
      val reported = if (scanPorts) SERVICE_PORTS else host.services.map { it.port }.toSet()
      val known = host.services.filter { it.port in reported }.ifEmpty { host.services }
      known.forEach { service ->
        if (serviceDetect) {
          appendLine("  ${service.port}/tcp  open  ${service.name}  ${service.version}")
        } else {
          appendLine("  ${service.port}/tcp  open  ${service.name}")
        }
      }
      if (scanPorts && reported.size > known.size) {
        appendLine("  (${reported.size - known.size} additional ports closed or filtered - simulated)")
      }
      appendLine()
      appendLine("Nmap done: 1 IP address (1 host up) scanned in 0.42 seconds (simulated)")
      appendLine("NOTE: this output is invented by the app. No network was touched.")
    }
  }

  /**
   * Renders a simulated `tcpdump` excerpt.
   *
   * The teaching point is the column layout: time, source, destination, flags and
   * length. An operator who has read this shape once recognises it immediately on
   * a real capture.
   */
  fun tcpdump(filter: String, count: Int): String {
    val host = LabNetwork.hostAt(filter) ?: LabNetwork.inventory.first { it.up }
    val services = host.services.ifEmpty { listOf(LabNetwork.Service(80, "http", "nginx 1.18.0")) }
    val lines = mutableListOf<String>()

    lines.add("tcpdump: verbose output suppressed, use -v or -vv for full output")
    lines.add("tcpdump: listening on any (simulated), link-type EN10MB, capture size 262144")
    lines.add("$filter: host $host (simulated capture)")
    lines.add("")

    // A repeating TCP handshake followed by payload, the shape everyone should
    // be able to read on sight.
    val rounds = count.coerceIn(1, MAX_PACKET_LINES / 5)
    for (round in 1..rounds) {
      val client = "10.10.14.200"
      val sport = 40000 + round * 7
      lines.add("${"%.3f".format(round * 0.0412)} IP ${client}.$sport > ${host.id}.${services[0].port}: Flags [S], seq ${round}000000, win 64240, length 0")
      lines.add("${"%.3f".format(round * 0.0412 + 0.0009)} IP ${host.id}.${services[0].port} > $client.$sport: Flags [S.], seq ${round}100000, ack ${round}000001, win 64240, length 0")
      lines.add("${"%.3f".format(round * 0.0412 + 0.0014)} IP ${client}.$sport > ${host.id}.${services[0].port}: Flags [.], ack ${round}100001, win 502, length 0")
      lines.add("${"%.3f".format(round * 0.0412 + 0.0420)} IP ${client}.$sport > ${host.id}.${services[0].port}: Flags [P.], seq ${round}000001, ack ${round}100001, win 502, length ${40 + round * 8}")
      lines.add("${"%.3f".format(round * 0.0412 + 0.0428)} IP ${host.id}.${services[0].port} > $client.$sport: Flags [P.], seq ${round}100001, ack ${round}00000${1 + round * 8}, win 460, length 420")
    }
    lines.add("")
    lines.add("${rounds * 5} packets captured (simulated)")
    lines.add("NOTE: this capture is invented by the app. No packet was received.")
    return lines.joinToString("\n")
  }

  /** Host-discovery sweep, `-sn`. */
  private fun hostDiscovery(target: String): String {
    val hosts = LabNetwork.inventory.filter { it.up }
    return buildString {
      appendLine("Starting Nmap 7.94 (simulated) at ${LabNetwork.SUBNET}.0/24 - host discovery")
      appendLine("Nmap scan report for ${LabNetwork.SUBNET}.0/24")
      hosts.forEach { host ->
        appendLine("Host ${host.id} is up - simulated latency ${String.format("%.1f", LabNetwork.latencyFor(host))} ms")
      }
      appendLine()
      appendLine("Nmap done: ${hosts.size} hosts up, ${LabNetwork.inventory.size - hosts.size} down (simulated)")
    }
  }

  private fun unknownHost(target: String): String =
    "Nmap scan report for $target\n" +
      "Host $target is down - not part of the simulated lab network.\n" +
      "The lab covers ${LabNetwork.SUBNET}.${LabNetwork.HOST_MIN}-${LabNetwork.HOST_MAX}.\n" +
      "NOTE: this output is invented by the app. No network was touched."

  /** The port set a `-p-` scan would consider, kept local so the catalog stays private. */
  private val SERVICE_PORTS = setOf(21, 22, 23, 53, 80, 139, 443, 445, 3306, 3389, 8080, 5432)
}
