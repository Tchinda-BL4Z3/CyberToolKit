package com.example.ssh

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SSH consent gate.
 *
 * These deliberately do not attempt a real connection. That would need a live
 * server, a network permission and a credential, and it would make the test suite
 * depend on the machine it runs on. What is verified here is the part that
 * actually matters for a shipped APK: without consent, no socket is opened.
 */
class SshSessionTest {

  @Test
  fun `refuses to connect while consent is off`() = runTest {
    val session = SshSession(UnconfinedTestDispatcher(testScheduler), consentGiven = false)
    val result = session.run(
      SshCredentials(
        host = "10.10.14.5",
        username = "operator",
        secret = "",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n-----END OPENSSH PRIVATE KEY-----"
      ),
      "id"
    )
    assertTrue(result is SshResult.Failure)
    assertEquals(SshSession.NOT_ENABLED, (result as SshResult.Failure).message)
  }

  @Test
  fun `a blank command is refused before any connection`() = runTest {
    // Consent is on so the failure can only come from the command check.
    val session = SshSession(UnconfinedTestDispatcher(testScheduler), consentGiven = true)
    val result = session.run(
      SshCredentials(host = "10.10.14.5", username = "operator", secret = ""),
      "   "
    )
    assertTrue(result is SshResult.Failure)
    assertEquals("Commande vide.", (result as SshResult.Failure).message)
  }

  @Test
  fun `a blank host is refused before any connection`() = runTest {
    val session = SshSession(UnconfinedTestDispatcher(testScheduler), consentGiven = true)
    val result = session.run(
      SshCredentials(host = "   ", username = "operator", secret = ""),
      "id"
    )
    assertTrue(result is SshResult.Failure)
    assertEquals("Hôte vide.", (result as SshResult.Failure).message)
  }

  @Test
  fun `a missing private key is reported as a failure, not a crash`() = runTest {
    val session = SshSession(UnconfinedTestDispatcher(testScheduler), consentGiven = true)
    val result = session.run(
      SshCredentials(host = "10.10.14.5", username = "operator", secret = "", useKeyAuth = true),
      "id"
    )
    assertTrue(result is SshResult.Failure)
    assertTrue((result as SshResult.Failure).message.contains("clé privée"))
  }

  @Test
  fun `an unreachable host surfaces a readable failure`() = runTest {
    // TEST-NET-1 (RFC 5737) is reserved and never routable, so this fails fast
    // without depending on anything on the developer's network.
    val session = SshSession(UnconfinedTestDispatcher(testScheduler), consentGiven = true)
    val result = session.run(
      SshCredentials(
        host = "192.0.2.1",
        port = 22,
        username = "operator",
        secret = "",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"
      ),
      "id"
    )
    assertTrue("expected a failure, got $result", result is SshResult.Failure)
    val message = (result as SshResult.Failure).message
    // Must be a sentence an operator can act on, not a stack trace.
    assertTrue(message.isNotBlank())
    assertTrue(!message.contains("at com.jcraft"))
  }

  @Test
  fun `consent defaults to off`() = runTest {
    // A caller that forgets to pass the flag gets the safe behaviour.
    val result = SshSession(UnconfinedTestDispatcher(testScheduler))
      .run(SshCredentials(host = "10.10.14.5", username = "operator", secret = ""), "id")
    assertEquals(SshSession.NOT_ENABLED, (result as SshResult.Failure).message)
  }
}
