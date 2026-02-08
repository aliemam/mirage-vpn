"""Scan Cloudflare IP ranges for working (unblocked) IPs.

For each IP in the given subnets:
  - Attempt a TLS handshake on port 443
  - Measure handshake latency
  - Output sorted list of working IPs

Default Cloudflare IPv4 ranges are used if no subnets file is provided.
"""

import asyncio
import ipaddress
import ssl
import time
from dataclasses import dataclass
from typing import Callable

# Cloudflare's published IPv4 ranges (subset of most common)
# Full list: https://www.cloudflare.com/ips-v4/
DEFAULT_SUBNETS = [
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "162.159.0.0/16",
    "198.41.128.0/17",
    "188.114.96.0/20",
    "190.93.240.0/20",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "131.0.72.0/22",
]


@dataclass
class IPResult:
    ip: str
    latency_ms: int = -1
    success: bool = False
    error: str = ""


def load_subnets(path: str | None = None) -> list[str]:
    """Load subnet list from file or use defaults."""
    if path:
        with open(path) as f:
            subnets = []
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    subnets.append(line)
            return subnets
    return list(DEFAULT_SUBNETS)


def expand_subnets(
    subnets: list[str], sample_per_subnet: int = 0,
) -> list[str]:
    """Expand CIDR subnets to individual IPs.

    Args:
        subnets: List of CIDR strings
        sample_per_subnet: If > 0, sample this many IPs per subnet instead of all.
                          0 means all IPs.
    """
    import random

    all_ips = []
    for subnet_str in subnets:
        net = ipaddress.ip_network(subnet_str, strict=False)
        hosts = list(net.hosts())
        if sample_per_subnet > 0 and len(hosts) > sample_per_subnet:
            hosts = random.sample(hosts, sample_per_subnet)
        all_ips.extend(str(ip) for ip in hosts)
    return all_ips


async def _test_ip(
    ip: str,
    port: int = 443,
    timeout: float = 5.0,
    sni: str = "speed.cloudflare.com",
) -> IPResult:
    """Test a single IP with a TLS handshake."""
    result = IPResult(ip=ip)
    ctx = ssl.create_default_context()

    try:
        start = time.monotonic()
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(ip, port, ssl=ctx, server_hostname=sni),
            timeout=timeout,
        )
        elapsed = time.monotonic() - start
        result.success = True
        result.latency_ms = int(elapsed * 1000)
        writer.close()
        await writer.wait_closed()
    except asyncio.TimeoutError:
        result.error = "timeout"
    except ssl.SSLError as e:
        result.error = f"ssl: {e}"
    except OSError as e:
        result.error = f"os: {e}"
    except Exception as e:
        result.error = str(e)

    return result


async def scan_ips(
    ips: list[str],
    concurrency: int = 50,
    port: int = 443,
    timeout: float = 5.0,
    sni: str = "speed.cloudflare.com",
    on_result: Callable[[IPResult, int, int], None] | None = None,
) -> list[IPResult]:
    """Scan a list of IPs with concurrency limit.

    Returns results sorted by latency (working IPs first).
    """
    sem = asyncio.Semaphore(concurrency)
    results: list[IPResult] = []
    completed = 0
    total = len(ips)
    lock = asyncio.Lock()

    async def _test(ip: str) -> None:
        nonlocal completed
        async with sem:
            r = await _test_ip(ip, port, timeout, sni)
            async with lock:
                completed += 1
                results.append(r)
                if on_result:
                    on_result(r, completed, total)

    tasks = [asyncio.create_task(_test(ip)) for ip in ips]
    await asyncio.gather(*tasks)

    # Sort: successful by latency, then failed
    working = sorted([r for r in results if r.success], key=lambda r: r.latency_ms)
    failed = [r for r in results if not r.success]
    return working + failed


async def scan_cloudflare(
    subnets_file: str | None = None,
    concurrency: int = 50,
    sample_per_subnet: int = 100,
    sni: str = "speed.cloudflare.com",
    on_result: Callable[[IPResult, int, int], None] | None = None,
) -> list[IPResult]:
    """High-level: scan Cloudflare subnets for clean IPs.

    Args:
        subnets_file: Path to file with CIDR ranges (one per line), or None for defaults
        concurrency: Max parallel connections
        sample_per_subnet: Number of random IPs to sample per subnet (0=all)
        sni: SNI for TLS handshake
        on_result: Progress callback
    """
    subnets = load_subnets(subnets_file)
    ips = expand_subnets(subnets, sample_per_subnet)
    return await scan_ips(ips, concurrency, sni=sni, on_result=on_result)


def export_clean_ips(
    results: list[IPResult], path: str = "clean_ips.txt", top_n: int = 50,
) -> None:
    """Export top N working IPs to a file."""
    working = [r for r in results if r.success][:top_n]
    with open(path, "w") as f:
        for r in working:
            f.write(f"{r.ip}  # {r.latency_ms}ms\n")
