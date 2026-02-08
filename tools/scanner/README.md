# MirageVPN Scanner

**Anti-Censorship Parameter Grid Search Tool**

Automatically tests thousands of xray/v2ray tunnel parameter combinations to find the settings that bypass DPI (Deep Packet Inspection) in censored networks. Works with VLESS, VMess, Trojan, and Shadowsocks protocols.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
  - [Option 1: Standalone Binary (Recommended for Iran)](#option-1-standalone-binary-recommended-for-iran)
  - [Option 2: Python venv](#option-2-python-venv)
- [Quick Start](#quick-start)
- [Usage Modes](#usage-modes)
  - [Scan Mode](#scan-mode)
  - [Clean IP Mode](#clean-ip-mode)
  - [Setup Mode (Coming Soon)](#setup-mode)
- [Interactive Wizard](#interactive-wizard)
- [CLI Reference](#cli-reference)
- [Parameters Reference](#parameters-reference)
  - [Fragment](#1-fragment)
  - [uTLS Fingerprint](#2-utls-fingerprint)
  - [ALPN](#3-alpn)
  - [ECH](#4-ech-encrypted-client-hello)
  - [Socket Options](#5-socket-options)
  - [Transport](#6-transport)
- [Search Modes](#search-modes)
  - [Smart Search](#smart-search)
  - [Full Grid Search](#full-grid-search)
  - [Quick Search](#quick-search)
- [Output Files](#output-files)
- [Supported Protocols](#supported-protocols)
- [Architecture](#architecture)
- [Xray Binary](#xray-binary)
- [FAQ](#faq)

---

## Overview

MirageVPN Scanner is a tool that finds the best anti-censorship tunnel settings for your specific network. ISPs use DPI to detect and block VPN traffic — this tool systematically tests different obfuscation parameters to find which combinations get through.

**The problem:** A proxy config that works today might get blocked tomorrow. Fragment sizes, TLS fingerprints, ALPN settings, and transport types all affect whether traffic gets through DPI.

**The solution:** Instead of manually guessing, this tool tests hundreds of parameter combinations in parallel and tells you which ones work — sorted by latency and speed.

## Features

- **Interactive wizard** — Just run `python scanner.py`, no flags needed
- **Multi-protocol** — VLESS (WS+TLS, REALITY), VMess, Trojan, Shadowsocks
- **Smart search** — Tests each parameter alone first, then combines winners (~200 tests vs 5000+)
- **Parallel testing** — Run 5-20 tests simultaneously
- **Live progress** — Real-time results table with colors as tests complete
- **Clean IP scanner** — Find unblocked Cloudflare edge IPs
- **Multiple output formats** — JSON results, ready-to-use proxy URIs, copy-paste for v2rayNG
- **Standalone binary** — Single executable file, no Python/Docker needed, works offline in Iran
- **Cross-platform** — Linux (amd64, arm64), macOS (Intel, Apple Silicon)

## Installation

### Option 1: Standalone Binary (Recommended for Iran)

**Single file, no dependencies.** Download one binary that bundles Python, all libraries, and xray-core. Nothing else needed — no Python, no Docker, no internet.

```bash
# Download the binary for your platform
# (from GitHub Releases or transferred via USB/Telegram/etc.)
chmod +x mirage-scanner-linux-amd64
./mirage-scanner-linux-amd64
```

Available binaries (built automatically by CI):
- `mirage-scanner-linux-amd64` — for most Linux PCs and VPS servers
- `mirage-scanner-linux-arm64` — for ARM servers (Oracle Cloud, Raspberry Pi)

To build it yourself from source:

```bash
cd tools/scanner
source .venv/bin/activate
pip install pyinstaller
bash build-standalone.sh
# Output: dist/mirage-scanner
```

### Option 2: Python venv

Run directly with Python. Requires Python 3.8+ and internet access for pip + xray download.

```bash
cd tools/scanner

# Create and activate virtual environment
python3 -m venv .venv
source .venv/bin/activate        # Linux/macOS
# .venv\Scripts\activate         # Windows

# Install dependencies
pip install -r requirements.txt

# Run
python scanner.py
```

The xray binary is downloaded automatically on first run. Or place it manually at `tools/scanner/bin/xray`.

## Quick Start

**Interactive mode** (recommended for first use):

```bash
python scanner.py
```

The wizard asks you everything step by step.

**CLI mode** (for automation/scripting):

```bash
# Test fragment + fingerprint params against a VLESS server
python scanner.py scan --uri "vless://uuid@host:443?security=tls&type=ws&..." --params fragment,fingerprint

# Quick scan with just the most impactful parameters
python scanner.py scan --uri "vless://..." --mode quick

# Find clean Cloudflare IPs
python scanner.py clean-ip

# Full grid search with speed measurement
python scanner.py scan --file configs.txt --mode full --speed --concurrency 20
```

## Usage Modes

### Scan Mode

Tests parameter combinations against existing proxy servers to find the best anti-DPI settings.

**What it does:**
1. Parses your proxy URI (VLESS, VMess, Trojan, or Shadowsocks)
2. Generates xray JSON configs with different parameter variations
3. Starts xray instances in parallel, each on a unique SOCKS5 port
4. Tests connectivity through each instance (HTTP request to `cp.cloudflare.com`)
5. Measures latency (time to first byte) and optionally download speed
6. Kills each xray instance after the test
7. Reports results sorted by latency, exports working configs as URIs

**Input:** A proxy URI or a `configs.txt` file with multiple URIs.

**Output:** `results.json` (all results) + `best_configs.txt` (top 5 working URIs).

### Clean IP Mode

Scans Cloudflare IP ranges to find IPs that are not blocked by your ISP. Useful for VLESS WebSocket+TLS configs where you connect to Cloudflare edge IPs.

**What it does:**
1. Takes Cloudflare CIDR ranges (built-in defaults or custom file)
2. Samples IPs from each range
3. Attempts TLS handshake on port 443 for each IP
4. Measures handshake latency
5. Outputs sorted list of working IPs

**Default Cloudflare ranges tested:**
- 104.16.0.0/13
- 104.24.0.0/14
- 172.64.0.0/13
- 162.159.0.0/16
- 198.41.128.0/17
- And more (see `clean_ip.py`)

**Output:** `clean_ips.txt` — one IP per line with latency.

```bash
# Use default Cloudflare ranges
python scanner.py clean-ip

# Use custom ranges file
python scanner.py clean-ip --subnets my-ranges.txt --concurrency 100

# Sample more IPs per subnet for thorough scan
python scanner.py clean-ip --sample 500
```

### Setup Mode

*(Coming in a future release)*

SSH into a VPS and automatically install/configure xray with REALITY, WS+TLS, or XHTTP, then immediately scan to find optimal parameters.

## Interactive Wizard

When you run `python scanner.py` without arguments, the interactive wizard guides you through every step:

```
╔══════════════════════════════════════════════════════════╗
║              MirageVPN Scanner v1.0                      ║
║         Anti-Censorship Parameter Grid Search            ║
╚══════════════════════════════════════════════════════════╝

? What would you like to do?
  > Scan — Test parameters against your server
    Clean IP — Find working Cloudflare IPs
    Setup — Install & configure a new server via SSH

? Enter your config URI (or path to configs.txt):
  > vless://1e7f9ff1-...@host:443?security=reality&...

? Which parameters do you want to test? (space to select)
  > [x] Fragment (size, interval, split type)
    [x] uTLS Fingerprint (chrome, firefox, qq, ...)
    [x] ALPN (h2, http/1.1)
    [ ] ECH (Encrypted Client Hello)
    [ ] Socket Options (TCP_NODELAY, TFO, MPTCP)
    [ ] Transport (ws, xhttp, tcp, grpc, h2)

? Search mode:
  > Smart (recommended) — Test each param alone, combine winners
    Full grid — Test ALL combinations (slow!)
    Quick — Only the most impactful params

? Parallel tests:
  > 10 (recommended)
```

The wizard then:
- Downloads xray-core if needed
- Tests the baseline config
- Runs the selected search mode with live progress
- Shows a results table
- Offers export options

## CLI Reference

### `scanner.py scan`

```
usage: scanner.py scan [-h] [--uri URI] [--file FILE] [--params PARAMS]
                       [--mode {smart,full,quick}] [--concurrency N]
                       [--timeout SECS] [--speed] [--output FILE] [--best FILE]

Options:
  --uri, -u URI           Proxy URI (vless://, vmess://, trojan://, ss://)
  --file, -f FILE         Path to configs.txt (one URI per line)
  --params, -p PARAMS     Comma-separated param groups to test
                          Default: fragment,fingerprint,alpn
                          Available: fragment, fingerprint, alpn, ech,
                                     socket_options, transport
  --mode, -m MODE         Search mode: smart (default), full, quick
  --concurrency, -c N     Parallel tests (default: 10)
  --timeout, -t SECS      Per-test timeout in seconds (default: 15)
  --speed                 Also measure download speed (slower)
  --output, -o FILE       JSON output file (default: results.json)
  --best, -b FILE         Best configs output (default: best_configs.txt)
```

### `scanner.py clean-ip`

```
usage: scanner.py clean-ip [-h] [--subnets FILE] [--concurrency N]
                            [--sample N] [--output FILE]

Options:
  --subnets, -s FILE      CIDR ranges file, one per line (default: built-in CF ranges)
  --concurrency, -c N     Parallel connections (default: 50)
  --sample N              IPs to test per subnet, 0=all (default: 100)
  --output, -o FILE       Output file (default: clean_ips.txt)
```

### `scanner.py setup`

```
usage: scanner.py setup [-h] [--ssh TARGET]

Options:
  --ssh TARGET            SSH target, e.g. root@1.2.3.4 (coming soon)
```

## Parameters Reference

These are the anti-censorship parameters the scanner tests. Each targets a different aspect of how DPI detects tunnel traffic.

### 1. Fragment

Splits the TLS ClientHello into smaller pieces to confuse DPI systems that inspect the first packet.

| Parameter | xray JSON Path | Test Values | What It Does |
|-----------|---------------|-------------|--------------|
| Length (packet size) | `sockopt.fragment.length` | `"1-1"`, `"10-20"`, `"50-100"`, `"100-200"`, `"200-400"`, `"517-517"` | Size of each fragment in bytes |
| Interval | `sockopt.fragment.interval` | `"1-1"`, `"2-5"`, `"5-10"`, `"10-20"`, `"20-50"` | Delay between fragments in ms |
| Packets (split type) | `sockopt.fragment.packets` | `"tlshello"`, `"1-2"`, `"1-3"`, `"1-5"` | Which packets to fragment: `tlshello` = only TLS handshake, `1-3` = packets 1 through 3 |

**How it works:** DPI often inspects the first packet (TLS ClientHello) to extract the SNI and detect proxy protocols. Fragmenting this packet into smaller pieces means no single piece contains the full SNI, so DPI can't extract it.

**Example xray JSON:**
```json
{
  "sockopt": {
    "fragment": {
      "packets": "tlshello",
      "length": "100-200",
      "interval": "5-10"
    }
  }
}
```

### 2. uTLS Fingerprint

Makes the TLS handshake look like it's coming from a real browser instead of an xray client.

| Parameter | xray JSON Path | Test Values |
|-----------|---------------|-------------|
| Fingerprint | `tlsSettings.fingerprint` | `"chrome"`, `"firefox"`, `"safari"`, `"edge"`, `"qq"`, `"random"`, `"ios"`, `"android"` |

**How it works:** Each browser has a unique TLS fingerprint (cipher suites, extensions, order). DPI can detect non-browser TLS clients. uTLS mimics real browser fingerprints to blend in.

### 3. ALPN

Application-Layer Protocol Negotiation — tells the server which HTTP version to use.

| Parameter | xray JSON Path | Test Values |
|-----------|---------------|-------------|
| ALPN | `tlsSettings.alpn` | `["h2"]`, `["http/1.1"]`, `["h2", "http/1.1"]` |

**How it works:** Some DPI systems treat HTTP/2 (`h2`) and HTTP/1.1 differently. Testing both reveals which is less likely to be flagged.

### 4. ECH (Encrypted Client Hello)

Encrypts the SNI in the TLS handshake so DPI can't see which domain you're connecting to.

| Parameter | xray JSON Path | Test Values |
|-----------|---------------|-------------|
| ECH enabled | `tlsSettings.ech.enabled` | `true`, `false` |
| DNS query for ECH config | `tlsSettings.ech.dnsQuery` | `true`, `false` |

**How it works:** Standard TLS sends the SNI (domain name) in plaintext. ECH encrypts it. Requires server support (mainly Cloudflare). The DNS query option fetches the ECH config from DNS before connecting.

### 5. Socket Options

Low-level TCP socket tweaks that can affect how traffic appears to DPI.

| Parameter | xray JSON Path | Test Values | What It Does |
|-----------|---------------|-------------|--------------|
| TCP_NODELAY | `sockopt.tcpNoDelay` | `true`, `false` | Disable Nagle's algorithm (send small packets immediately) |
| TCP Fast Open | `sockopt.tcpFastOpen` | `true`, `false` | Send data in the SYN packet (faster handshake) |
| MPTCP | `sockopt.tcpMptcp` | `true`, `false` | Multipath TCP (use multiple network paths) |
| Keep Alive | `sockopt.tcpKeepAliveInterval` | `0`, `15`, `30`, `60` | Seconds between keep-alive probes |
| Domain Strategy | `sockopt.domainStrategy` | `"AsIs"`, `"UseIP"`, `"UseIPv4"` | How to resolve domain names |

### 6. Transport

The underlying transport protocol for the tunnel.

| Parameter | xray JSON Path | Test Values |
|-----------|---------------|-------------|
| Transport type | `streamSettings.network` | `"ws"`, `"xhttp"`, `"tcp"`, `"grpc"`, `"h2"` |
| XHTTP mode | `xhttpSettings.mode` | `"auto"`, `"packet-up"`, `"stream-up"` |

**Note:** Changing transport requires server-side support. Only test transport types your server is configured for.

## Search Modes

### Smart Search

**Recommended.** Three phases:

1. **Baseline** — Test your config as-is to establish a reference
2. **Individual sweep** — Test each parameter dimension alone (e.g., all fragment sizes, then all fingerprints, etc.)
3. **Combine winners** — Take the top 3 from each dimension and test all their combinations

**Typical test count:** ~200 tests for fragment + fingerprint + alpn

**Why it works:** Most parameters are independent. If `chrome` fingerprint works but `safari` doesn't, that's true regardless of fragment settings. By testing dimensions separately first, we avoid wasting time on combinations where one parameter is already known to fail.

### Full Grid Search

Tests ALL possible combinations (cartesian product).

**Example:** 6 fragment lengths x 5 intervals x 4 packet types x 8 fingerprints x 3 ALPNs = **2,880 tests**

Use this when you suspect parameters interact in unexpected ways, or when you have plenty of time and want exhaustive coverage. With `--concurrency 20`, this runs 20 tests at a time.

### Quick Search

Tests only the three most impactful parameters: fragment, fingerprint, and ALPN. Uses the same smart search algorithm but with a reduced parameter set.

**Typical test count:** ~50 tests

Use this for a fast first pass to see if anything works at all.

## Output Files

### `results.json`

Complete test results in JSON format:

```json
[
  {
    "params": {
      "fragment_length": "100-200",
      "fragment_interval": "5-10",
      "fragment_packets": "tlshello",
      "fingerprint": "chrome",
      "alpn": ["h2"]
    },
    "description": "fragment_length=100-200 + fingerprint=chrome + alpn=['h2']",
    "success": true,
    "latency_ms": 195,
    "speed_mbps": 2.3,
    "error": ""
  }
]
```

### `best_configs.txt`

Top 5 working configs as proxy URIs, ready to import into v2rayNG, v2rayN, Nekoray, or any xray/v2ray client:

```
vless://uuid@host:443?security=tls&type=ws&fp=chrome&alpn=h2&...#1-195ms
vless://uuid@host:443?security=tls&type=ws&fp=firefox&alpn=h2&...#2-280ms
```

### `clean_ips.txt`

Working Cloudflare IPs sorted by latency:

```
104.17.23.45  # 89ms
172.64.155.12  # 102ms
104.24.88.201  # 115ms
```

## Supported Protocols

The scanner parses and generates configs for all major xray protocols:

| Protocol | URI Format | Example |
|----------|-----------|---------|
| VLESS WebSocket+TLS | `vless://uuid@host:port?security=tls&type=ws&...` | Cloudflare CDN configs |
| VLESS REALITY | `vless://uuid@host:port?security=reality&pbk=...&sid=...` | Direct server configs |
| VMess | `vmess://base64(json)` | Legacy v2ray configs |
| Trojan | `trojan://password@host:port?sni=...` | Trojan-Go compatible |
| Shadowsocks | `ss://base64(method:password)@host:port` | SIP002 format |

The URI formats match those used by v2rayNG, v2rayN, Nekoray, and the MirageVPN Android app.

## Architecture

```
tools/scanner/
├── scanner.py              # Entry point — wizard or CLI
├── wizard.py               # Interactive TUI (questionary + rich)
├── uri_parser.py           # Parse/build proxy URIs (all protocols)
├── config_generator.py     # Generate xray JSON with param variations
├── tester.py               # Async xray process mgmt + connectivity tests
├── grid_search.py          # Smart/full/quick search orchestration
├── report.py               # Rich tables + JSON/URI export
├── clean_ip.py             # Cloudflare IP scanner
├── xray_downloader.py      # Auto-download xray-core binary
├── ssh_manager.py          # Server setup via SSH (Phase 2)
├── mirage-scanner.spec     # PyInstaller spec for standalone binary
├── build-standalone.sh     # Build script for standalone binary
├── requirements.txt        # Python dependencies
└── README.md               # This file
```

**How a scan works internally:**

1. `uri_parser.py` parses the proxy URI into a config dict
2. `config_generator.py` generates xray JSON configs with different parameter combinations
3. `tester.py` writes each JSON to a temp file, starts xray on a unique port, tests connectivity via SOCKS5, measures latency, kills xray
4. `grid_search.py` orchestrates which tests to run in which order (smart/full/quick)
5. `report.py` displays live progress and final results

Each test is independent — xray instances run on ports 20000-30000 and don't interfere with each other.

## Xray Binary

The scanner needs the xray-core binary to run tests. It looks in this order:

1. `tools/scanner/bin/xray` — local to the scanner
2. `xray` on your `$PATH` — system-wide install
3. **Auto-download** from GitHub releases — detected platform (linux-64, linux-arm64, macos-64, macos-arm64)

**Docker:** The image bundles xray-core, no download needed.

**Manual install:** Download from [github.com/XTLS/Xray-core/releases](https://github.com/XTLS/Xray-core/releases) and place the binary at `tools/scanner/bin/xray`.

## FAQ

**Q: How many tests does smart mode run?**
Depends on which parameters you select. Fragment + fingerprint + ALPN with smart mode = ~200 tests. With 10 parallel tests and 15s timeout, that's roughly 5 minutes.

**Q: Can I test with multiple configs?**
Yes — pass a `configs.txt` file with `--file`. The scanner currently uses the first config as the base for parameter variations. Multi-config grid search (testing each config with each parameter set) is planned.

**Q: Does this work on Windows?**
The Python code works on Windows, but xray auto-download currently supports Linux and macOS. On Windows, manually download `xray.exe` from the xray-core releases and place it in `tools/scanner/bin/`.

**Q: What ports does the scanner use?**
Each parallel xray instance uses a unique port in the 20000-30000 range. These are local-only (127.0.0.1) SOCKS5 ports. Make sure this range is free.

**Q: Can I stop a scan midway?**
Yes — press Ctrl+C. Results collected so far are lost (they're in memory). Future versions will save partial results.

**Q: What's the difference between fragment and ECH?**
Fragment splits the TLS ClientHello into pieces so DPI can't read the SNI from a single packet. ECH encrypts the SNI entirely. They can be used together. Fragment is more widely effective; ECH requires Cloudflare server support.

**Q: What if no configs work?**
Your server may be fully blocked at the IP level. Try:
1. Clean IP mode to find working Cloudflare IPs
2. REALITY protocol (direct server, not Cloudflare)
3. A different server/VPS provider

