package com.example.ui.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the simulated network tools.
 *
 * The critical assertions are the negative ones: a tool that invents results must
 * say so in its output, and must refuse anything outside the invented range. If a
 * future change drops those guarantees, the tool starts lying, and these fail.
 */
class LabToolsTest {

  private val upHost = LabNetwork.inventory.first { it.up }
  private val downHost = LabNetwork.inventory.first { !it.up }

  @Test
  fun `ping on a live host reports replies and no loss`() {
    val out = LabTools.ping(upHost.id)
    assertTrue(out.contains("4 packets transmitted, 4 received"))
    assertTrue(out.contains("0% packet loss"))
    assertTrue(out.contains("icmp_seq=1"))
  }

  @Test
  fun `ping on a dead host reports total loss`() {
    val out = LabTools.ping(downHost.id)
    assertTrue(out.contains("100% packet loss"))
    assertTrue(out.contains("no route to host"))
  }

  @Test
  fun `ping outside the lab range is refused, not invented`() {
    val out = LabTools.ping("192.168.1.1")
    assertTrue(out.contains("not part of the simulated lab network"))
    assertTrue(out.contains("No network was touched"))
  }

  @Test
  fun `ping is deterministic across calls`() {
    assertEquals(LabTools.ping(upHost.id), LabTools.ping(upHost.id))
  }

  @Test
  fun `nmap reports open ports for a live host`() {
    val out = LabTools.nmap(upHost.id, emptyList())
    assertTrue(out.contains("is up"))
    upHost.services.forEach { service ->
      assertTrue("port $service missing", out.contains("${service.port}/tcp"))
    }
  }

  @Test
  fun `nmap -sV adds version strings`() {
    val out = LabTools.nmap(upHost.id, listOf("-sV"))
    assertTrue(out.contains("/tcp  open  "))
    upHost.services.forEach { service ->
      assertTrue("version for ${service.port} missing", out.contains(service.version))
    }
  }

  @Test
  fun `nmap -sn discovers only live hosts`() {
    val out = LabTools.nmap("${LabNetwork.SUBNET}.0/24", listOf("-sn"))
    assertTrue(out.contains("is up"))
    assertTrue(out.contains("hosts up"))
    downHost.id.let { id ->
      assertTrue("down host $id wrongly reported up", !out.contains("Host $id is up"))
    }
  }

  @Test
  fun `nmap always discloses that it is simulated`() {
    listOf(emptyList(), listOf("-sV"), listOf("-p-")).forEach { flags ->
      assertTrue(LabTools.nmap(upHost.id, flags).contains("No network was touched"))
    }
  }

  @Test
  fun `nmap on a dead host says down rather than listing ports`() {
    val out = LabTools.nmap(downHost.id, emptyList())
    assertTrue(out.contains("is down"))
    assertTrue(!out.contains("/tcp  open"))
  }

  @Test
  fun `nmap on a cidr sweep enumerates live hosts`() {
    val out = LabTools.nmap("${LabNetwork.SUBNET}.0/24", emptyList())
    LabNetwork.inventory.filter { it.up }.forEach { host ->
      assertTrue("missing ${host.id}", out.contains("Host ${host.id} up"))
    }
  }

  @Test
  fun `tcpdump prints the column layout operators must recognise`() {
    val out = LabTools.tcpdump(upHost.id, 2)
    assertTrue(out.contains("tcpdump: listening on any"))
    assertTrue(out.contains("Flags [S]"))
    assertTrue(out.contains("Flags [S.]"))
    assertTrue(out.contains("Flags [P.]"))
    assertTrue(out.contains("packets captured (simulated)"))
  }

  @Test
  fun `tcpdump honours the packet count`() {
    assertTrue(LabTools.tcpdump(upHost.id, 3).contains("15 packets captured"))
    assertTrue(LabTools.tcpdump(upHost.id, 99).contains("20 packets captured"))
  }

  @Test
  fun `tcpdump always discloses the capture is invented`() {
    assertTrue(LabTools.tcpdump(upHost.id, 1).contains("No packet was received"))
  }

  @Test
  fun `inventory is stable across accesses`() {
    assertEquals(LabNetwork.inventory, LabNetwork.inventory)
    assertTrue(LabNetwork.inventory.all { it.id.startsWith("${LabNetwork.SUBNET}.") })
  }

  @Test
  fun `every host address is inside the advertised range`() {
    LabNetwork.inventory.forEach { host ->
      val last = host.id.substringAfterLast('.').toInt()
      assertTrue(last in LabNetwork.HOST_MIN..LabNetwork.HOST_MAX)
    }
  }

  @Test
  fun `address parsing accepts the short form and rejects the long one`() {
    assertEquals(upHost.id, LabNetwork.hostAt(upHost.id.substringAfterLast('.'))?.id)
    assertTrue(LabNetwork.hostAt("1.2.3.4") == null)
    assertTrue(LabNetwork.hostAt("999") == null)
  }

  @Test
  fun `service ports are unique per host and sorted`() {
    LabNetwork.inventory.forEach { host ->
      val ports = host.services.map { it.port }
      assertEquals(ports.sorted(), ports)
      assertEquals(ports.distinct().size, ports.size)
    }
  }
}
