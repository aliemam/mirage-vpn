"""Rich terminal output: live progress tables, final reports, JSON/URI export."""

import json
from pathlib import Path

from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

from tester import TestResult

console = Console()


def make_result_row(r: TestResult) -> list[str]:
    """Extract display values from a TestResult for table rows."""
    # Parse overrides for display columns
    params = r.params or {}
    frag = ""
    if "fragment_length" in params:
        frag = f"{params['fragment_length']}/{params.get('fragment_interval', '')}"
    packets = params.get("fragment_packets", "")
    fp = params.get("fingerprint", "")
    alpn = params.get("alpn", "")
    if isinstance(alpn, list):
        alpn = ",".join(alpn)

    status = "[green]OK[/green]" if r.success else f"[red]{r.error or 'FAIL'}[/red]"
    latency = f"{r.latency_ms}ms" if r.latency_ms >= 0 else "-"
    speed = f"{r.speed_mbps}" if r.speed_mbps >= 0 else "-"

    return [frag, packets, fp, alpn, status, latency, speed]


def build_results_table(
    results: list[TestResult], title: str = "Results",
) -> Table:
    """Build a rich Table from test results."""
    table = Table(title=title, show_lines=True)
    table.add_column("#", style="dim", width=4)
    table.add_column("Fragment", min_width=10)
    table.add_column("Packets", min_width=8)
    table.add_column("FP", min_width=8)
    table.add_column("ALPN", min_width=8)
    table.add_column("Status", min_width=8)
    table.add_column("Latency", min_width=8, justify="right")
    table.add_column("Speed", min_width=6, justify="right")

    for i, r in enumerate(results, 1):
        row = make_result_row(r)
        table.add_row(str(i), *row)

    return table


def show_final_report(results: list[TestResult]) -> None:
    """Display final results table and summary stats."""
    # Sort: successful first by latency, then failed
    working = sorted(
        [r for r in results if r.success], key=lambda r: r.latency_ms,
    )
    failed = [r for r in results if not r.success]
    sorted_results = working + failed

    # Show top results table
    top_n = min(20, len(sorted_results))
    if top_n > 0:
        table = build_results_table(
            sorted_results[:top_n],
            title=f"Top {top_n} Results",
        )
        console.print()
        console.print(table)

    # Summary panel
    total = len(results)
    ok_count = len(working)
    blocked = len(failed)

    if working:
        best = working[0]
        best_desc = best.description or "baseline"
        best_lat = f"{best.latency_ms}ms"
    else:
        best_desc = "none"
        best_lat = "-"

    summary = (
        f"Tested: {total} configs | "
        f"[green]Working: {ok_count}[/green] | "
        f"[red]Blocked: {blocked}[/red]\n"
        f"Best: {best_desc} ({best_lat})"
    )
    console.print()
    console.print(Panel(summary, title="Summary", border_style="bright_cyan"))


def show_progress_result(result: TestResult, completed: int, total: int) -> None:
    """Print a single test result as it completes."""
    pct = int(completed / total * 100) if total > 0 else 0
    bar_len = 30
    filled = int(bar_len * completed / total) if total > 0 else 0
    bar = "█" * filled + "░" * (bar_len - filled)

    if result.success:
        status = f"[green]✓[/green] {result.latency_ms}ms"
        if result.speed_mbps >= 0:
            status += f" {result.speed_mbps}MB/s"
    else:
        status = f"[red]✗[/red] {result.error or 'BLOCKED'}"

    desc = result.description[:50] if result.description else "test"
    console.print(
        f"  {bar} {completed}/{total} ({pct}%) "
        f"{desc}: {status}",
    )


def show_phase(name: str, phase_num: int, total: int) -> None:
    """Display a phase header."""
    console.print()
    console.rule(f"Phase {phase_num}/{total}: {name}")
    console.print()


def export_json(
    results: list[TestResult], path: str = "results.json",
) -> None:
    """Export all results to JSON."""
    data = []
    for r in results:
        entry = {
            "params": r.params,
            "description": r.description,
            "success": r.success,
            "latency_ms": r.latency_ms,
            "speed_mbps": r.speed_mbps,
            "error": r.error,
        }
        # Serialize non-JSON-safe values
        for k, v in entry["params"].items():
            if isinstance(v, bool):
                entry["params"][k] = v
        data.append(entry)

    with open(path, "w") as f:
        json.dump(data, f, indent=2, default=str)
    console.print(f"  Full results saved to: [cyan]{path}[/cyan]")


def export_best_uris(
    results: list[TestResult],
    base_config: dict,
    path: str = "best_configs.txt",
    top_n: int = 5,
) -> None:
    """Export top N working configs as proxy URIs with overrides in the name."""
    from config_generator import build_xray_json
    from uri_parser import build_uri

    working = sorted(
        [r for r in results if r.success], key=lambda r: r.latency_ms,
    )

    lines = []
    for i, r in enumerate(working[:top_n], 1):
        # Build a modified config dict with the winning params
        modified = dict(base_config)
        overrides = r.params or {}

        # Apply fingerprint and alpn overrides to the config for URI output
        if "fingerprint" in overrides:
            modified["fingerprint"] = overrides["fingerprint"]
        if "alpn" in overrides:
            alpn = overrides["alpn"]
            if isinstance(alpn, list):
                modified["alpn"] = ",".join(alpn)
            else:
                modified["alpn"] = alpn

        # Include latency + overrides in the name
        desc_short = r.description[:40] if r.description else ""
        modified["name"] = f"#{i}-{r.latency_ms}ms-{desc_short}"

        try:
            uri = build_uri(modified)
            lines.append(uri)
        except Exception:
            lines.append(f"# Error building URI for result #{i}")

    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")

    console.print(f"  Best configs saved to: [cyan]{path}[/cyan]")
