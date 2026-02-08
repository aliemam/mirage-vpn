"""Interactive TUI wizard using questionary + rich.

Guides users through scan/setup/clean-ip modes with colorful prompts.
"""

import asyncio
import sys

import questionary
from questionary import Style
from rich.console import Console
from rich.panel import Panel

from uri_parser import parse_multi, parse_uri

console = Console()

# Custom questionary style
STYLE = Style([
    ("qmark", "fg:ansicyan bold"),
    ("question", "bold"),
    ("answer", "fg:ansicyan"),
    ("pointer", "fg:ansicyan bold"),
    ("highlighted", "fg:ansicyan bold"),
    ("selected", "fg:ansigreen"),
    ("separator", "fg:ansibrightblack"),
    ("instruction", "fg:ansibrightblack"),
])

BANNER = """
╔══════════════════════════════════════════════════════════╗
║              MirageVPN Scanner v1.0                      ║
║         Anti-Censorship Parameter Grid Search            ║
╚══════════════════════════════════════════════════════════╝
"""

PARAM_CHOICES = [
    questionary.Choice("Fragment Length (packet size: 1-1, 10-20, 100-200, ...)", value="fragment_length"),
    questionary.Choice("Fragment Interval (delay: 1-1ms, 2-5ms, 10-20ms, ...)", value="fragment_interval"),
    questionary.Choice("Fragment Packets (split: tlshello, 1-2, 1-3, 1-5)", value="fragment_packets"),
    questionary.Choice("uTLS Fingerprint (chrome, firefox, safari, qq, ...)", value="fingerprint"),
    questionary.Choice("ALPN (h2, http/1.1)", value="alpn"),
    questionary.Choice("ECH (Encrypted Client Hello)", value="ech"),
    questionary.Choice("Socket Options (TCP_NODELAY, TFO, MPTCP)", value="socket_options"),
    questionary.Choice("Transport (ws, xhttp, tcp, grpc, h2)", value="transport"),
]


def show_banner() -> None:
    """Display the ASCII art banner."""
    console.print(BANNER, style="bright_cyan")


def ask_mode() -> str:
    """Ask what the user wants to do."""
    return questionary.select(
        "What would you like to do?",
        choices=[
            questionary.Choice("Scan — Test parameters against your server", value="scan"),
            questionary.Choice("Clean IP — Find working Cloudflare IPs", value="clean_ip"),
            questionary.Choice("Setup — Install & configure a new server via SSH", value="setup"),
        ],
        style=STYLE,
    ).ask()


def ask_config_input() -> list[dict]:
    """Ask for config URI or file path, return parsed configs."""
    while True:
        answer = questionary.text(
            "Enter your config URI (or path to configs.txt):",
            style=STYLE,
        ).ask()

        if answer is None:
            sys.exit(0)

        answer = answer.strip()
        if not answer:
            console.print("  [red]Please enter a URI or file path.[/red]")
            continue

        # Try as a file path
        try:
            with open(answer) as f:
                configs = parse_multi(f.read())
                if configs:
                    console.print(
                        f"  [green]Loaded {len(configs)} configs from file.[/green]",
                    )
                    return configs
        except (FileNotFoundError, IsADirectoryError, PermissionError):
            pass

        # Try as a single URI
        parsed = parse_uri(answer)
        if parsed:
            parsed["_raw_uri"] = answer
            protocol = parsed["protocol"]
            security = parsed.get("security", "")
            addr = parsed["address"]
            console.print(
                f"  [green]Parsed {protocol}"
                f"{'+' + security if security else ''}"
                f" config → {addr}[/green]",
            )
            return [parsed]

        console.print(
            "  [red]Could not parse as URI or config file. "
            "Supported: vless://, vmess://, trojan://, ss://[/red]",
        )


def ask_params() -> list[str]:
    """Ask which parameter groups to test."""
    result = questionary.checkbox(
        "Which parameters do you want to test? (space to select)",
        choices=PARAM_CHOICES,
        style=STYLE,
    ).ask()

    if not result:
        console.print("  [yellow]No params selected, using recommended defaults.[/yellow]")
        return ["fragment_length", "fragment_interval", "fragment_packets",
                "fingerprint", "alpn"]

    return result


def ask_search_mode() -> str:
    """Ask for search mode."""
    return questionary.select(
        "Search mode:",
        choices=[
            questionary.Choice(
                "Smart (recommended) — Test each param alone, combine winners",
                value="smart",
            ),
            questionary.Choice(
                "Full grid — Test ALL combinations (slow!)",
                value="full",
            ),
            questionary.Choice(
                "Quick — Only the most impactful params",
                value="quick",
            ),
        ],
        style=STYLE,
    ).ask()


def ask_concurrency() -> int:
    """Ask for parallel test count."""
    answer = questionary.select(
        "Parallel tests (how many simultaneous):",
        choices=[
            questionary.Choice("10 (recommended for most connections)", value="10"),
            questionary.Choice("5  (slower connection / limited resources)", value="5"),
            questionary.Choice("20 (fast connection / powerful machine)", value="20"),
        ],
        style=STYLE,
    ).ask()
    return int(answer) if answer else 10


def ask_measure_speed() -> bool:
    """Ask if speed should be measured (slower but more info)."""
    return questionary.confirm(
        "Measure download speed? (slower but gives throughput data)",
        default=False,
        style=STYLE,
    ).ask()


def ask_clean_ip_options() -> dict:
    """Ask clean IP scan options."""
    subnets = questionary.text(
        "Path to subnets file (leave empty for default Cloudflare ranges):",
        style=STYLE,
    ).ask()

    concurrency = questionary.select(
        "Parallel connections:",
        choices=[
            questionary.Choice("20 (recommended)", value="20"),
            questionary.Choice("10 (conservative / avoid rate limits)", value="10"),
            questionary.Choice("50 (fast, may trigger rate limits)", value="50"),
        ],
        style=STYLE,
    ).ask()

    sample = questionary.select(
        "IPs per subnet to test:",
        choices=[
            questionary.Choice("100 (quick sample)", value="100"),
            questionary.Choice("500 (thorough)", value="500"),
            questionary.Choice("All (complete scan, slow)", value="0"),
        ],
        style=STYLE,
    ).ask()

    return {
        "subnets_file": subnets.strip() if subnets and subnets.strip() else None,
        "concurrency": int(concurrency),
        "sample_per_subnet": int(sample),
    }


def ask_post_scan() -> str:
    """Ask what to do after scan completes."""
    return questionary.select(
        "What next?",
        choices=[
            questionary.Choice(
                "Export best config as URI (copy-paste to v2ray client)",
                value="export_uri",
            ),
            questionary.Choice(
                "Test a different server",
                value="rescan",
            ),
            questionary.Choice("Exit", value="exit"),
        ],
        style=STYLE,
    ).ask()


def run_scan_wizard() -> dict:
    """Run the full scan wizard flow and return options dict."""
    configs = ask_config_input()
    params = ask_params()
    mode = ask_search_mode()
    concurrency = ask_concurrency()
    measure_speed = ask_measure_speed()

    return {
        "action": "scan",
        "configs": configs,
        "params": params,
        "mode": mode,
        "concurrency": concurrency,
        "measure_speed": measure_speed,
    }


def run_clean_ip_wizard() -> dict:
    """Run the clean IP wizard flow."""
    options = ask_clean_ip_options()
    return {"action": "clean_ip", **options}


def run_wizard() -> dict:
    """Main wizard entry point. Returns options dict for the chosen action."""
    show_banner()

    mode = ask_mode()
    if mode is None:
        sys.exit(0)

    if mode == "scan":
        return run_scan_wizard()
    elif mode == "clean_ip":
        return run_clean_ip_wizard()
    elif mode == "setup":
        console.print(
            "\n  [yellow]Server setup mode is coming in a future release.[/yellow]",
        )
        console.print(
            "  [dim]For now, use the scan mode to test parameters "
            "against existing servers.[/dim]\n",
        )
        sys.exit(0)
    else:
        sys.exit(0)
