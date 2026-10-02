package com.example.ui.lab

/**
 * A fictional network, invented once and entirely in memory.
 *
 * ### Why this exists
 *
 * The app ships with no `INTERNET` permission, so it cannot scan a real host.
 * That is a deliberate, defensible position - but it left the lab shell with
 * nothing to teach beyond `ls` and `cat`. This class fills that gap honestly:
 * every host, port and packet below is fabricated by the app itself.
 *
 * ### What this is not
 *
 * It is not a scanner. Nothing here opens a socket, resolves a name, or touches
 * a network stack. `nmap 10.10.14.5` in this lab reports what [topology] says
 * about the invented host of that name, and nothing else. The distinction is
 * load-bearing, so the UI labels the whole screen as a simulation.
 *
 * ### Determinism
 *
 * Everything is derived from a fixed seed ([SEED]) rather than a random source.
 * A scan of the same target returns the same ports on every run, on every
 * device, forever. A learning tool that lied differently each time would teach
 * the wrong instinct.
 */
object LabNetwork {

  /** Fixed seed: results must not vary between runs. */
  private const val SEED = 0x5EED_1234

  /** Hosts live in this range, the private 10.10.14.0/24 used by the notes. */
  const val SUBNET = "10.10.14"
  const val HOST_MIN = 1
  const val HOST_MAX = 40

  /**
   * A service the fictional hosts can offer.
   *
   * [open] and [version] are per-host, drawn from the seed, so a port that is open
   * on one host is closed on the next - which is the pattern that makes real
   * scanning interesting.
   */
  data class Service(
    val port: Int,
    val name: String,
    val version: String
  )

  /**
   * One invented machine.
   *
   * @param id dotted host number, e.g. `10.10.14.5`
   * @param services what this particular host happens to expose
   */
  data class Host(
    val id: String,
    val label: String,
    val up: Boolean,
    val services: List<Service>
  )

  private val SERVICE_CATALOG = listOf(
    21 to ("ftp" to "vsftpd 3.0.3"),
    22 to ("ssh" to "OpenSSH 8.9p1 Ubuntu-3ubuntu0.4"),
    23 to ("telnet" to "Linux telnetd"),
    53 to ("domain" to "dnsmasq 2.86"),
    80 to ("http" to "nginx 1.18.0"),
    139 to ("netbios-ssn" to "Samba smbd 4.6.2"),
    443 to ("https" to "Apache 2.4.57"),
    445 to ("microsoft-ds" to "Samba smbd 4.6.2"),
    3306 to ("mysql" to "MySQL 8.0.33"),
    3389 to ("ms-wbt-server" to "xfreerdp"),
    8080 to ("http-proxy" to "squid 5.7"),
    5432 to ("postgresql" to "PostgreSQL 15.4")
  )

  /**
   * Builds the deterministic host list.
   *
   * A small linear congruential generator seeded per host: cheap, dependency-free
   * and reproducible, which is all that is needed for a fixed scenario.
   */
  private fun hosts(): List<Host> = (HOST_MIN..HOST_MAX).map { n ->
    val rng = Lcg(SEED xor (n * 0x9E3779B1u.toInt()))
    val id = "$SUBNET.$n"
    // Roughly one host in five is down, so a sweep finds gaps.
    val up = rng.nextInt(100) >= 20
    val count = if (up) 1 + rng.nextInt(4) else 0
    val services = if (up) {
      SERVICE_CATALOG
        .filter { rng.nextInt(100) < 35 }
        .take(count.coerceAtLeast(1))
        .map { (port, pair) -> Service(port, pair.first, pair.second) }
        .sortedBy { it.port }
    } else {
      emptyList()
    }
    Host(
      id = id,
      label = HOST_NOTES[id] ?: "lab host $n",
      up = up,
      services = services
    )
  }

  /** Human notes for a few hosts, so a sweep has something recognisable in it. */
  private val HOST_NOTES = mapOf(
    "10.10.14.5" to "web target (nginx)",
    "10.10.14.12" to "lab domain controller",
    "10.10.14.23" to "database server",
    "10.10.14.31" to "jump box, ssh only",
    "10.10.14.40" to "decoy, answers ICMP"
  )

  /** The full invented inventory, built once. */
  val inventory: List<Host> by lazy { hosts() }

  /** Looks up one invented host, or null when the address is outside the lab. */
  fun hostAt(address: String): Host? {
    val n = address.trim().removePrefix("$SUBNET.")
    val number = n.toIntOrNull() ?: return null
    if (number !in HOST_MIN..HOST_MAX) return null
    return inventory.firstOrNull { it.id == "$SUBNET.$number" }
  }

  /** True when the string looks like an address inside the lab subnet. */
  fun isLabAddress(input: String): Boolean = hostAt(input) != null

  /**
   * A tiny reproducible PRNG.
   *
   * [nextInt] uses the standard LCG constants; the 32-bit overflow is intended
   * and keeps results identical on every JVM and Android version.
   */
  private class Lcg(private var state: Int) {
    fun nextInt(bound: Int): Int {
      state = (state * 1_103_515_245 + 12_345) and 0x7FFF_FFFF
      return if (bound <= 0) 0 else state % bound
    }
  }

  /** Deterministic round-trip time for a host, in milliseconds. */
  fun latencyFor(host: Host): Double {
    val rng = Lcg(SEED xor host.id.hashCode())
    if (!host.up) return Double.POSITIVE_INFINITY
    return 0.4 + rng.nextInt(180) / 100.0
  }
}
