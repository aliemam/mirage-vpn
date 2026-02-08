"""Run xray instances and test connectivity with latency/speed measurements.

Each test:
1. Writes xray JSON config to a temp file
2. Starts xray subprocess on a unique SOCKS port
3. Tests connectivity through the SOCKS proxy
4. Measures latency (time to first byte) and optionally speed
5. Kills xray, cleans up
"""

import asyncio
import json
import os
import signal
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import aiohttp
from aiohttp_socks import ProxyConnector


@dataclass
class TestResult:
    params: dict = field(default_factory=dict)
    description: str = ""
    success: bool = False
    latency_ms: int = -1
    speed_mbps: float = -1.0
    error: str = ""


# Connectivity test URL (Google's 204 endpoint — same as Android app)
TEST_URL = "https://www.google.com/generate_204"
# Speed test URL (1MB file from Cloudflare)
SPEED_URL = "https://speed.cloudflare.com/__down?bytes=1048576"

# Port range for parallel xray instances
_BASE_PORT = 20000
_port_counter = 0
_port_lock = asyncio.Lock()


async def _next_port() -> int:
    global _port_counter
    async with _port_lock:
        port = _BASE_PORT + (_port_counter % 10000)
        _port_counter += 1
        return port


async def test_single(
    xray_json: dict,
    xray_bin: str,
    timeout: int = 15,
    measure_speed: bool = False,
) -> TestResult:
    """Start xray, test connectivity, measure latency, return result.

    Args:
        xray_json: Complete xray config dict
        xray_bin: Path to xray binary
        timeout: Max seconds for the entire test
        measure_speed: Whether to also measure download speed
    """
    port = await _next_port()

    # Rewrite the inbound port in the config
    config = json.loads(json.dumps(xray_json))
    if config.get("inbounds"):
        config["inbounds"][0]["port"] = port

    # Write config to temp file
    config_path = os.path.join(tempfile.gettempdir(), f"mirage_scan_{port}.json")
    with open(config_path, "w") as f:
        json.dump(config, f)

    proc = None
    result = TestResult(
        params=xray_json.get("_overrides", {}),
        description=xray_json.get("_description", ""),
    )

    try:
        # Start xray
        proc = await asyncio.create_subprocess_exec(
            xray_bin, "run", "-c", config_path,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )

        # Wait for xray to start listening
        await asyncio.sleep(1.5)

        # Check if xray crashed
        if proc.returncode is not None:
            result.error = f"xray exited with code {proc.returncode}"
            return result

        # Test connectivity through SOCKS proxy
        connector = ProxyConnector.from_url(f"socks5://127.0.0.1:{port}")
        async with aiohttp.ClientSession(connector=connector) as session:
            # Latency test
            start = time.monotonic()
            async with session.get(
                TEST_URL, timeout=aiohttp.ClientTimeout(total=timeout),
            ) as resp:
                await resp.read()
                elapsed = time.monotonic() - start

            if resp.status in (200, 204):
                result.success = True
                result.latency_ms = int(elapsed * 1000)
            else:
                result.error = f"HTTP {resp.status}"
                return result

            # Speed test (optional)
            if measure_speed:
                try:
                    start = time.monotonic()
                    async with session.get(
                        SPEED_URL,
                        timeout=aiohttp.ClientTimeout(total=timeout),
                    ) as resp:
                        data = await resp.read()
                        elapsed = time.monotonic() - start
                    if elapsed > 0:
                        result.speed_mbps = round(
                            (len(data) * 8) / (elapsed * 1_000_000), 2,
                        )
                except Exception:
                    pass  # Speed test failure doesn't invalidate the config

    except asyncio.TimeoutError:
        result.error = "timeout"
    except aiohttp.ClientError as e:
        result.error = str(e)
    except Exception as e:
        result.error = str(e)
    finally:
        # Kill xray process
        if proc and proc.returncode is None:
            try:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), timeout=3)
                except asyncio.TimeoutError:
                    proc.kill()
                    await proc.wait()
            except ProcessLookupError:
                pass

        # Clean up temp config
        try:
            os.unlink(config_path)
        except OSError:
            pass

    return result


async def run_batch(
    tests: list[dict],
    xray_bin: str,
    concurrency: int = 10,
    timeout: int = 15,
    measure_speed: bool = False,
    on_result: Callable[[TestResult, int, int], None] | None = None,
) -> list[TestResult]:
    """Run tests with semaphore-limited concurrency.

    Args:
        tests: List of xray JSON config dicts (with _overrides and _description)
        xray_bin: Path to xray binary
        concurrency: Max parallel tests
        timeout: Per-test timeout in seconds
        measure_speed: Whether to measure download speed
        on_result: Callback(result, completed_count, total_count) for live updates
    """
    sem = asyncio.Semaphore(concurrency)
    results: list[TestResult] = []
    completed = 0
    total = len(tests)
    lock = asyncio.Lock()

    async def _run_one(xray_json: dict) -> TestResult:
        nonlocal completed
        async with sem:
            result = await test_single(
                xray_json, xray_bin, timeout, measure_speed,
            )
            async with lock:
                completed += 1
                results.append(result)
                if on_result:
                    on_result(result, completed, total)
            return result

    tasks = [asyncio.create_task(_run_one(t)) for t in tests]
    await asyncio.gather(*tasks)

    return results


async def test_base_config(
    xray_json: dict, xray_bin: str, timeout: int = 15,
) -> TestResult:
    """Quick test of the base config to verify it works at all."""
    return await test_single(xray_json, xray_bin, timeout, measure_speed=False)
