package com.example.payloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the Payloads tab.
 *
 * Two properties matter. The validator must reject anything that is not a
 * syntactically valid target, because the value is interpolated straight into a
 * shell one-liner; and a blank host/port must degrade to a `LHOST`/`LPORT`
 * template rather than to an empty or broken command.
 */
class PayloadGeneratorTest {

  // ---- host validation -----------------------------------------------------

  @Test
  fun `valid hostnames and IPv4 addresses are accepted`() {
    listOf(
      "target.lan",
      "10.0.0.5",
      "192.168.1.1",
      "localhost",
      "my-host-01.example.com"
    ).forEach { host ->
      assertEquals("expected valid: $host", HostValidation.Valid, PayloadGenerator.validateHost(host))
    }
  }

  @Test
  fun `surrounding whitespace is tolerated`() {
    assertEquals(HostValidation.Valid, PayloadGenerator.validateHost("  10.0.0.5  "))
  }

  @Test
  fun `a blank host is distinguished from an invalid one`() {
    // The UI needs the difference: blank means "not filled in yet", invalid
    // means "you typed something that cannot work".
    assertEquals(HostValidation.Empty, PayloadGenerator.validateHost(""))
    assertEquals(HostValidation.Empty, PayloadGenerator.validateHost("   "))
  }

  @Test
  fun `malformed and oversized hosts are rejected`() {
    listOf(
      "a".repeat(254),
      "host with spaces",
      "target.lan;rm -rf /",
      "target.lan|whoami",
      "192.168.1.999",
      "-target.lan",
      "target..lan",
      "10.0.0.5 && id",
      "\$(whoami)",
      // Underscores are not legal in a hostname (RFC 1123); the value ends up
      // interpolated into a shell one-liner, so anything odd is refused.
      "my_host.example.com"
    ).forEach { host ->
      assertEquals("expected invalid: '$host'", HostValidation.Invalid, PayloadGenerator.validateHost(host))
    }
  }

  @Test
  fun `the shipped default host passes validation`() {
    assertEquals(HostValidation.Valid, PayloadGenerator.validateHost("192.168.1.100"))
  }

  // ---- port validation -----------------------------------------------------

  @Test
  fun `ports in range are accepted`() {
    listOf("1", "80", "4444", "65535").forEach { port ->
      assertEquals("expected valid: $port", PortValidation.Valid, PayloadGenerator.validatePort(port))
    }
  }

  @Test
  fun `ports out of range report the parsed value`() {
    assertEquals(PortValidation.OutOfRange(0), PayloadGenerator.validatePort("0"))
    assertEquals(PortValidation.OutOfRange(65536), PayloadGenerator.validatePort("65536"))
    assertEquals(PortValidation.OutOfRange(99999), PayloadGenerator.validatePort("99999"))
  }

  @Test
  fun `a blank or non-numeric port is treated as not filled in`() {
    listOf("", "   ", "80;whoami", "8 0", "8.0", "http").forEach { port ->
      assertEquals("expected empty: '$port'", PortValidation.Empty, PayloadGenerator.validatePort(port))
    }
  }

  // ---- generation ----------------------------------------------------------

  @Test
  fun `a valid target embeds the host and the port`() {
    val command = PayloadGenerator.generate(PayloadEnvironment.BASH, "10.1.2.3", "4444")
    assertTrue(command.contains("10.1.2.3"))
    assertTrue(command.contains("4444"))
  }

  @Test
  fun `every environment produces a non-empty command`() {
    PayloadEnvironment.entries.forEach { environment ->
      val command = PayloadGenerator.generate(environment, "10.1.2.3", "4444")
      assertTrue("${environment.key} produced nothing", command.isNotBlank())
      assertTrue(
        "${environment.key} dropped the host",
        command.contains("10.1.2.3")
      )
    }
  }

  @Test
  fun `a blank target degrades to a copyable placeholder template`() {
    listOf(PayloadEnvironment.BASH, PayloadEnvironment.PYTHON, PayloadEnvironment.NETCAT)
      .forEach { environment ->
        val command = PayloadGenerator.generate(environment, "", "")
        assertTrue("${environment.key} lost LHOST", command.contains("LHOST"))
        assertTrue("${environment.key} lost LPORT", command.contains("LPORT"))
      }
  }

  @Test
  fun `generation is pure, it is repeatable for the same input`() {
    val environment = PayloadEnvironment.PYTHON
    assertEquals(
      PayloadGenerator.generate(environment, "10.1.2.3", "4444"),
      PayloadGenerator.generate(environment, "10.1.2.3", "4444")
    )
  }

  @Test
  fun `a PowerShell command does not let the host break out of the quotes`() {
    val command = PayloadGenerator.generate(PayloadEnvironment.POWERSHELL, "10.1.2.3", "4444")
    assertTrue(command.contains("'10.1.2.3',4444"))
  }

  // ---- environment lookup --------------------------------------------------

  @Test
  fun `fromKey resolves known keys and falls back to Bash`() {
    PayloadEnvironment.entries.forEach {
      assertEquals(it, PayloadEnvironment.fromKey(it.key))
    }
    assertEquals(PayloadEnvironment.BASH, PayloadEnvironment.fromKey("not-a-shell"))
    assertEquals(PayloadEnvironment.BASH, PayloadEnvironment.fromKey(""))
  }

  @Test
  fun `every environment has a distinct key and label`() {
    assertEquals(PayloadEnvironment.entries.size, PayloadEnvironment.entries.map { it.key }.toSet().size)
    assertEquals(PayloadEnvironment.entries.size, PayloadEnvironment.entries.map { it.label }.toSet().size)
  }
}
