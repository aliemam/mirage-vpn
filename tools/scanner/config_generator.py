"""Generate xray JSON configs with parameter variations for grid search.

Builds complete xray-core config JSON with SOCKS5 inbound and protocol-specific
outbound, then injects sockopt.fragment, tlsSettings overrides, etc.
"""

import copy
import itertools

PARAM_SPACE = {
    "fragment_length": ["1-1", "10-20", "50-100", "100-200", "200-400", "517-517"],
    "fragment_interval": ["1-1", "2-5", "5-10", "10-20", "20-50"],
    "fragment_packets": ["tlshello", "1-2", "1-3", "1-5"],
    "fingerprint": [
        "chrome", "firefox", "safari", "edge", "qq", "random", "ios", "android",
    ],
    "alpn": [["h2"], ["http/1.1"], ["h2", "http/1.1"]],
    "ech_enabled": [True, False],
    "ech_dns": [True, False],
    "tcp_no_delay": [True, False],
    "tcp_fast_open": [True, False],
    "mptcp": [True, False],
    "tcp_keep_alive": [0, 15, 30, 60],
    "domain_strategy": ["AsIs", "UseIP", "UseIPv4"],
    "transport": ["ws", "xhttp", "tcp", "grpc", "h2"],
    "xhttp_mode": ["auto", "packet-up", "stream-up"],
}

# Which user-facing parameter groups map to which param_space keys
PARAM_GROUPS = {
    "fragment_length": ["fragment_length"],
    "fragment_interval": ["fragment_interval"],
    "fragment_packets": ["fragment_packets"],
    "fingerprint": ["fingerprint"],
    "alpn": ["alpn"],
    "ech": ["ech_enabled", "ech_dns"],
    "socket_options": [
        "tcp_no_delay", "tcp_fast_open", "mptcp",
        "tcp_keep_alive", "domain_strategy",
    ],
    "transport": ["transport", "xhttp_mode"],
}

# Most impactful params for quick mode
QUICK_PARAMS = ["fragment_length", "fragment_interval", "fragment_packets",
                "fingerprint", "alpn"]


def build_xray_json(config: dict, overrides: dict, socks_port: int) -> dict:
    """Generate a complete xray JSON config.

    Args:
        config: Parsed URI config dict from uri_parser.parse_uri()
        overrides: Parameter overrides to apply (from PARAM_SPACE keys)
        socks_port: Local SOCKS5 port for xray inbound
    """
    protocol = config["protocol"]

    if protocol == "vless":
        outbound = _build_vless_outbound(config, overrides)
    elif protocol == "vmess":
        outbound = _build_vmess_outbound(config, overrides)
    elif protocol == "trojan":
        outbound = _build_trojan_outbound(config, overrides)
    elif protocol == "shadowsocks":
        outbound = _build_shadowsocks_outbound(config, overrides)
    else:
        raise ValueError(f"Unsupported protocol: {protocol}")

    return {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {
                "tag": "socks-in",
                "port": socks_port,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "settings": {
                    "auth": "noauth",
                    "udp": True,
                },
            }
        ],
        "outbounds": [outbound],
    }


def _build_vless_outbound(config: dict, overrides: dict) -> dict:
    """Build VLESS outbound (WebSocket+TLS or REALITY)."""
    security = config.get("security", "tls")
    transport = overrides.get("transport", config.get("transport", "tcp"))

    vnext = {
        "address": config["address"],
        "port": config["port"],
        "users": [
            {
                "id": config["uuid"],
                "encryption": "none",
            }
        ],
    }

    if security == "reality":
        vnext["users"][0]["flow"] = config.get("flow", "xtls-rprx-vision")

    stream = _build_stream_settings(config, overrides, security, transport)

    return {
        "tag": "proxy",
        "protocol": "vless",
        "settings": {"vnext": [vnext]},
        "streamSettings": stream,
    }


def _build_vmess_outbound(config: dict, overrides: dict) -> dict:
    """Build VMess outbound."""
    transport = overrides.get("transport", config.get("transport", "ws"))
    tls_type = config.get("tls", "tls")
    security_type = "tls" if tls_type == "tls" else "none"

    vnext = {
        "address": config["address"],
        "port": config["port"],
        "users": [
            {
                "id": config["uuid"],
                "alterId": config.get("alter_id", 0),
                "security": config.get("security", "auto"),
            }
        ],
    }

    stream = _build_stream_settings(config, overrides, security_type, transport)

    return {
        "tag": "proxy",
        "protocol": "vmess",
        "settings": {"vnext": [vnext]},
        "streamSettings": stream,
    }


def _build_trojan_outbound(config: dict, overrides: dict) -> dict:
    """Build Trojan outbound."""
    transport = overrides.get("transport", config.get("transport", "tcp"))
    tls_type = config.get("tls", "tls")
    security_type = "tls" if tls_type == "tls" else "none"

    server = {
        "address": config["address"],
        "port": config["port"],
        "password": config["password"],
    }

    stream = _build_stream_settings(config, overrides, security_type, transport)

    return {
        "tag": "proxy",
        "protocol": "trojan",
        "settings": {"servers": [server]},
        "streamSettings": stream,
    }


def _build_shadowsocks_outbound(config: dict, overrides: dict) -> dict:
    """Build Shadowsocks outbound."""
    server = {
        "address": config["address"],
        "port": config["port"],
        "method": config["method"],
        "password": config["password"],
    }

    stream = {"network": "tcp"}
    _apply_sockopt(stream, overrides)

    return {
        "tag": "proxy",
        "protocol": "shadowsocks",
        "settings": {"servers": [server]},
        "streamSettings": stream,
    }


def _build_stream_settings(
    config: dict, overrides: dict, security: str, transport: str,
) -> dict:
    """Build streamSettings with transport, TLS, and socket options."""
    stream: dict = {"network": transport}

    # Transport settings
    if transport == "ws":
        ws_settings = {
            "path": config.get("path", "/"),
            "headers": {},
        }
        if config.get("host"):
            ws_settings["headers"]["Host"] = config["host"]
        stream["wsSettings"] = ws_settings

    elif transport == "grpc":
        stream["grpcSettings"] = {
            "serviceName": config.get("path", ""),
        }

    elif transport == "h2":
        h2_settings = {
            "path": config.get("path", "/"),
        }
        if config.get("host"):
            h2_settings["host"] = [config["host"]]
        stream["httpSettings"] = h2_settings

    elif transport == "xhttp":
        xhttp_settings = {
            "path": config.get("path", "/"),
        }
        if config.get("host"):
            xhttp_settings["host"] = config["host"]
        xhttp_mode = overrides.get("xhttp_mode")
        if xhttp_mode:
            xhttp_settings["mode"] = xhttp_mode
        stream["xhttpSettings"] = xhttp_settings

    # TLS / REALITY settings
    if security == "reality":
        fp = overrides.get("fingerprint", config.get("fingerprint", "chrome"))
        stream["security"] = "reality"
        stream["realitySettings"] = {
            "serverName": config.get("sni", ""),
            "fingerprint": fp,
            "publicKey": config.get("public_key", ""),
            "shortId": config.get("short_id", ""),
        }
    elif security == "tls":
        fp = overrides.get("fingerprint", config.get("fingerprint", ""))
        alpn = overrides.get("alpn")
        if alpn is None:
            alpn_str = config.get("alpn", "")
            alpn = alpn_str.split(",") if alpn_str else []

        tls_settings: dict = {
            "serverName": config.get("sni", config["address"]),
        }
        if fp:
            tls_settings["fingerprint"] = fp
        if alpn:
            tls_settings["alpn"] = alpn

        # ECH settings
        if overrides.get("ech_enabled"):
            tls_settings["ech"] = {
                "enabled": True,
                "dnsQuery": overrides.get("ech_dns", False),
            }

        stream["security"] = "tls"
        stream["tlsSettings"] = tls_settings
    else:
        stream["security"] = "none"

    # Socket options (fragment, TCP tweaks)
    _apply_sockopt(stream, overrides, config)

    # allowInsecure
    if config.get("allow_insecure") and "tlsSettings" in stream:
        stream["tlsSettings"]["allowInsecure"] = True

    return stream


def _apply_sockopt(stream: dict, overrides: dict, config: dict | None = None) -> None:
    """Apply sockopt overrides (fragment, TCP options) to stream settings.

    If the original config has fragment settings (from URI), those are used as
    defaults. Overrides take priority over config defaults.
    """
    sockopt: dict = {}
    cfg = config or {}

    # Fragment — override > config default > nothing
    frag_len = overrides.get("fragment_length", cfg.get("fragment_length"))
    frag_int = overrides.get("fragment_interval", cfg.get("fragment_interval"))
    frag_pkt = overrides.get("fragment_packets", cfg.get("fragment_packets"))
    if frag_len or frag_int or frag_pkt:
        sockopt["fragment"] = {
            "packets": frag_pkt or "tlshello",
            "length": frag_len or "100-200",
            "interval": frag_int or "10-20",
        }

    # TCP options
    if "tcp_no_delay" in overrides:
        sockopt["tcpNoDelay"] = overrides["tcp_no_delay"]
    if "tcp_fast_open" in overrides:
        sockopt["tcpFastOpen"] = overrides["tcp_fast_open"]
    if "mptcp" in overrides:
        sockopt["tcpMptcp"] = overrides["mptcp"]
    if "tcp_keep_alive" in overrides:
        sockopt["tcpKeepAliveInterval"] = overrides["tcp_keep_alive"]
    if "domain_strategy" in overrides:
        sockopt["domainStrategy"] = overrides["domain_strategy"]

    if sockopt:
        stream["sockopt"] = sockopt


def generate_smart_grid(
    config: dict, param_groups: list[str],
) -> list[tuple[dict, str]]:
    """Generate test cases for smart search mode.

    Phase 1: Test each parameter dimension independently.
    Returns list of (overrides_dict, description_string).
    """
    tests = []

    # Baseline (no overrides)
    tests.append(({}, "baseline (no changes)"))

    for group_name in param_groups:
        keys = PARAM_GROUPS.get(group_name, [group_name])
        for key in keys:
            if key not in PARAM_SPACE:
                continue
            for value in PARAM_SPACE[key]:
                overrides = {key: value}
                desc = f"{key}={value}"
                tests.append((overrides, desc))

    return tests


def generate_combination_grid(
    winners: dict[str, list[tuple[dict, float]]],
    top_n: int = 3,
) -> list[tuple[dict, str]]:
    """Generate combination tests from per-dimension winners.

    Args:
        winners: {param_group: [(overrides, latency_ms), ...]} sorted by latency
        top_n: How many top winners per dimension to combine
    """
    tests = []

    # Get top winners per dimension
    dimension_tops: dict[str, list[dict]] = {}
    for group, results in winners.items():
        dimension_tops[group] = [r[0] for r in results[:top_n]]

    if not dimension_tops:
        return tests

    # Generate combinations across dimensions
    dims = list(dimension_tops.keys())
    value_lists = [dimension_tops[d] for d in dims]

    for combo in itertools.product(*value_lists):
        merged: dict = {}
        desc_parts = []
        for dim_overrides in combo:
            merged.update(dim_overrides)
            for k, v in dim_overrides.items():
                desc_parts.append(f"{k}={v}")
        tests.append((merged, " + ".join(desc_parts)))

    return tests


def generate_full_grid(
    config: dict, param_groups: list[str],
) -> list[tuple[dict, str]]:
    """Generate ALL combinations (cartesian product). Can be very large."""
    all_keys = []
    all_values = []

    for group_name in param_groups:
        keys = PARAM_GROUPS.get(group_name, [group_name])
        for key in keys:
            if key not in PARAM_SPACE:
                continue
            all_keys.append(key)
            all_values.append(PARAM_SPACE[key])

    if not all_keys:
        return [({}, "baseline")]

    tests = []
    for combo in itertools.product(*all_values):
        overrides = dict(zip(all_keys, combo))
        desc = ", ".join(f"{k}={v}" for k, v in overrides.items())
        tests.append((overrides, desc))

    return tests


def generate_quick_grid(config: dict) -> list[tuple[dict, str]]:
    """Generate tests for just the most impactful params."""
    return generate_smart_grid(config, QUICK_PARAMS)
