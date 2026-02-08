#!/usr/bin/env python3
"""MirageVPN Scanner — Anti-Censorship Parameter Grid Search Tool.

Interactive TUI or CLI-driven tool for testing anti-censorship tunnel
parameters (fragment, fingerprint, ALPN, ECH, socket options, transport)
against xray-based proxy configs.

Usage:
    python scanner.py                  # Interactive wizard
    python scanner.py scan --uri URI   # CLI mode
    python scanner.py clean-ip         # Cloudflare IP scanner
"""

import argparse
import asyncio
import sys
from pathlib import Path

from rich.console import Console

console = Console()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="MirageVPN Scanner — Anti-Censorship Parameter Grid Search",
    )
    sub = parser.add_subparsers(dest="command")

    # ── scan ───────────────────────────────────────────────────────────
    scan_p = sub.add_parser("scan", help="Test parameters against your server")
    scan_p.add_argument(
        "--uri", "-u",
        help="Proxy URI (vless://, vmess://, trojan://, ss://)",
    )
    scan_p.add_argument(
        "--file", "-f",
        help="Path to configs.txt with one URI per line",
    )
    scan_p.add_argument(
        "--params", "-p",
        default="fragment,fingerprint,alpn",
        help="Comma-separated param groups to test "
             "(fragment,fingerprint,alpn,ech,socket_options,transport)",
    )
    scan_p.add_argument(
        "--mode", "-m",
        choices=["smart", "full", "quick"],
        default="smart",
        help="Search mode (default: smart)",
    )
    scan_p.add_argument(
        "--concurrency", "-c",
        type=int, default=10,
        help="Parallel tests (default: 10)",
    )
    scan_p.add_argument(
        "--timeout", "-t",
        type=int, default=15,
        help="Per-test timeout in seconds (default: 15)",
    )
    scan_p.add_argument(
        "--speed",
        action="store_true",
        help="Also measure download speed (slower)",
    )
    scan_p.add_argument(
        "--output", "-o",
        default="results.json",
        help="Output JSON file (default: results.json)",
    )
    scan_p.add_argument(
        "--best", "-b",
        default="best_configs.txt",
        help="Output best configs file (default: best_configs.txt)",
    )

    # ── clean-ip ───────────────────────────────────────────────────────
    ip_p = sub.add_parser("clean-ip", help="Scan Cloudflare IP ranges")
    ip_p.add_argument(
        "--subnets", "-s",
        help="Path to file with CIDR ranges (one per line)",
    )
    ip_p.add_argument(
        "--concurrency", "-c",
        type=int, default=50,
        help="Parallel connections (default: 50)",
    )
    ip_p.add_argument(
        "--sample", type=int, default=100,
        help="IPs per subnet to test (0=all, default: 100)",
    )
    ip_p.add_argument(
        "--output", "-o",
        default="clean_ips.txt",
        help="Output file (default: clean_ips.txt)",
    )

    # ── setup ──────────────────────────────────────────────────────────
    setup_p = sub.add_parser(
        "setup", help="Install & configure a server via SSH (coming soon)",
    )
    setup_p.add_argument("--ssh", help="SSH target (e.g., root@1.2.3.4)")

    return parser.parse_args()


async def cmd_scan(args: argparse.Namespace) -> None:
    """Run scan from CLI args."""
    from uri_parser import parse_multi, parse_uri
    from xray_downloader import ensure_xray
    from grid_search import smart_search, full_search, quick_search
    from report import (
        export_best_uris, export_json, show_final_report,
        show_phase, show_progress_result,
    )

    # Parse config(s)
    configs = []
    if args.uri:
        parsed = parse_uri(args.uri)
        if not parsed:
            console.print(f"[red]Failed to parse URI: {args.uri}[/red]")
            sys.exit(1)
        parsed["_raw_uri"] = args.uri
        configs = [parsed]
    elif args.file:
        with open(args.file) as f:
            configs = parse_multi(f.read())
        if not configs:
            console.print(f"[red]No valid configs found in {args.file}[/red]")
            sys.exit(1)
    else:
        console.print("[red]Provide --uri or --file[/red]")
        sys.exit(1)

    console.print(f"  Loaded {len(configs)} config(s)")

    # Ensure xray binary
    xray_bin = ensure_xray()
    console.print(f"  Using xray: [cyan]{xray_bin}[/cyan]")

    params = [p.strip() for p in args.params.split(",")]
    config = configs[0]  # Use first config as base

    # Run search
    search_fn = {
        "smart": lambda: smart_search(
            config, params, xray_bin, args.concurrency,
            args.timeout, args.speed, show_phase, show_progress_result,
        ),
        "full": lambda: full_search(
            config, params, xray_bin, args.concurrency,
            args.timeout, args.speed, show_phase, show_progress_result,
        ),
        "quick": lambda: quick_search(
            config, xray_bin, args.concurrency,
            args.timeout, args.speed, show_phase, show_progress_result,
        ),
    }

    results = await search_fn[args.mode]()

    # Output
    show_final_report(results)
    export_json(results, args.output)
    export_best_uris(results, config, args.best)


async def cmd_clean_ip(args: argparse.Namespace) -> None:
    """Run clean IP scan from CLI args."""
    from clean_ip import export_clean_ips, scan_cloudflare
    from report import console

    console.print("\n  [cyan]Scanning Cloudflare IP ranges...[/cyan]\n")

    def on_result(r, completed, total):
        if completed % 50 == 0 or completed == total:
            pct = int(completed / total * 100) if total > 0 else 0
            status = f"[green]✓ {r.latency_ms}ms[/green]" if r.success else "[red]✗[/red]"
            console.print(f"  {completed}/{total} ({pct}%) {r.ip}: {status}")

    results = await scan_cloudflare(
        subnets_file=args.subnets,
        concurrency=args.concurrency,
        sample_per_subnet=args.sample,
        on_result=on_result,
    )

    working = [r for r in results if r.success]
    console.print(
        f"\n  [green]Found {len(working)} working IPs[/green] "
        f"out of {len(results)} tested",
    )

    if working:
        # Show top 10
        from rich.table import Table
        table = Table(title="Top Clean IPs")
        table.add_column("#", width=4)
        table.add_column("IP", min_width=15)
        table.add_column("Latency", justify="right")
        for i, r in enumerate(working[:10], 1):
            table.add_row(str(i), r.ip, f"{r.latency_ms}ms")
        console.print(table)

    export_clean_ips(results, args.output)
    console.print(f"  Results saved to: [cyan]{args.output}[/cyan]")


async def cmd_scan_wizard(options: dict) -> None:
    """Run scan from wizard options."""
    from xray_downloader import ensure_xray
    from grid_search import smart_search, full_search, quick_search
    from report import (
        export_best_uris, export_json, show_final_report,
        show_phase, show_progress_result,
    )

    configs = options["configs"]
    config = configs[0]

    console.print()
    console.rule("Preparing")

    # Ensure xray
    xray_bin = ensure_xray()
    console.print(f"  Using xray: [cyan]{xray_bin}[/cyan]")
    console.print(f"  Testing {config['protocol']} config → {config['address']}")
    console.print()

    params = options["params"]
    mode = options["mode"]
    concurrency = options["concurrency"]
    measure_speed = options["measure_speed"]

    search_fn = {
        "smart": lambda: smart_search(
            config, params, xray_bin, concurrency,
            15, measure_speed, show_phase, show_progress_result,
        ),
        "full": lambda: full_search(
            config, params, xray_bin, concurrency,
            15, measure_speed, show_phase, show_progress_result,
        ),
        "quick": lambda: quick_search(
            config, xray_bin, concurrency,
            15, measure_speed, show_phase, show_progress_result,
        ),
    }

    results = await search_fn[mode]()

    show_final_report(results)
    export_json(results)
    export_best_uris(results, config)

    # Post-scan menu
    from wizard import ask_post_scan
    while True:
        action = ask_post_scan()
        if action == "export_uri":
            working = sorted(
                [r for r in results if r.success], key=lambda r: r.latency_ms,
            )
            if working:
                from uri_parser import build_uri
                best = dict(config)
                overrides = working[0].params or {}
                if "fingerprint" in overrides:
                    best["fingerprint"] = overrides["fingerprint"]
                if "alpn" in overrides:
                    alpn = overrides["alpn"]
                    best["alpn"] = ",".join(alpn) if isinstance(alpn, list) else alpn
                best["name"] = f"best-{working[0].latency_ms}ms"
                uri = build_uri(best)
                console.print(f"\n  [green]{uri}[/green]\n")
            else:
                console.print("\n  [red]No working configs found.[/red]\n")
        elif action == "rescan":
            return  # Let main loop re-run wizard
        else:
            break


async def cmd_clean_ip_wizard(options: dict) -> None:
    """Run clean IP scan from wizard options."""
    from clean_ip import export_clean_ips, scan_cloudflare
    from rich.table import Table

    console.print("\n  [cyan]Scanning Cloudflare IP ranges...[/cyan]\n")

    def on_result(r, completed, total):
        if completed % 50 == 0 or completed == total:
            pct = int(completed / total * 100) if total > 0 else 0
            status = f"[green]✓ {r.latency_ms}ms[/green]" if r.success else "[red]✗[/red]"
            console.print(f"  {completed}/{total} ({pct}%) {r.ip}: {status}")

    results = await scan_cloudflare(
        subnets_file=options.get("subnets_file"),
        concurrency=options.get("concurrency", 50),
        sample_per_subnet=options.get("sample_per_subnet", 100),
        on_result=on_result,
    )

    working = [r for r in results if r.success]
    console.print(
        f"\n  [green]Found {len(working)} working IPs[/green] "
        f"out of {len(results)} tested",
    )

    if working:
        table = Table(title="Top Clean IPs")
        table.add_column("#", width=4)
        table.add_column("IP", min_width=15)
        table.add_column("Latency", justify="right")
        for i, r in enumerate(working[:10], 1):
            table.add_row(str(i), r.ip, f"{r.latency_ms}ms")
        console.print(table)

    export_clean_ips(results)
    console.print("  Results saved to: [cyan]clean_ips.txt[/cyan]")


def main() -> None:
    args = parse_args()

    if args.command == "scan":
        asyncio.run(cmd_scan(args))
    elif args.command == "clean-ip":
        asyncio.run(cmd_clean_ip(args))
    elif args.command == "setup":
        console.print(
            "\n  [yellow]Server setup mode is coming in a future release.[/yellow]\n",
        )
        sys.exit(0)
    else:
        # No subcommand — launch interactive wizard
        from wizard import run_wizard

        while True:
            options = run_wizard()
            action = options.get("action")

            if action == "scan":
                asyncio.run(cmd_scan_wizard(options))
            elif action == "clean_ip":
                asyncio.run(cmd_clean_ip_wizard(options))
            else:
                break


if __name__ == "__main__":
    main()
