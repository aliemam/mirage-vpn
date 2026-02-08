"""SSH into VPS, install and configure xray/paqet server (Phase 2).

This module is a placeholder for future server setup functionality.
It will support:
  - Installing xray-core via SSH
  - Generating REALITY keypairs
  - Setting up WS+TLS / XHTTP / REALITY configs
  - Installing Paqet KCP tunnels
  - Checking server status
"""

# Phase 2 — not yet implemented


def setup_server(ssh_target: str, protocol: str = "reality") -> None:
    """Connect to a VPS via SSH and set up xray/paqet.

    Args:
        ssh_target: SSH target string (e.g., "root@1.2.3.4")
        protocol: Protocol to set up (reality, ws-tls, xhttp, paqet)
    """
    raise NotImplementedError(
        "Server setup mode is coming in a future release. "
        "For now, use the scan mode to test parameters against existing servers."
    )
