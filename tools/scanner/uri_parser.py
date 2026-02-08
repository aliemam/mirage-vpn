"""Parse and build vless/vmess/trojan/ss proxy URIs.

Matches the URI formats used by the MirageVPN Android app
(RemoteConfigFetcher.kt) and common v2ray/xray clients.
"""

import base64
import json
import re
from urllib.parse import (
    parse_qs,
    quote,
    unquote,
    urlparse,
)


def parse_uri(uri: str) -> dict | None:
    """Parse a proxy URI into a config dict.

    Supports: vless://, vmess://, trojan://, ss://
    Returns None if parsing fails.
    """
    uri = uri.strip()
    if uri.startswith("vless://"):
        return _parse_vless(uri)
    elif uri.startswith("vmess://"):
        return _parse_vmess(uri)
    elif uri.startswith("trojan://"):
        return _parse_trojan(uri)
    elif uri.startswith("ss://"):
        return _parse_shadowsocks(uri)
    return None


def build_uri(config: dict) -> str:
    """Convert a config dict back to a URI string."""
    protocol = config.get("protocol", "")
    if protocol == "vless":
        return _build_vless(config)
    elif protocol == "vmess":
        return _build_vmess(config)
    elif protocol == "trojan":
        return _build_trojan(config)
    elif protocol == "shadowsocks":
        return _build_shadowsocks(config)
    raise ValueError(f"Unknown protocol: {protocol}")


def config_id(uri: str) -> str:
    """Generate a stable ID for a config URI (strip fragment, base64 encode)."""
    stripped = uri.split("#")[0]
    return base64.b64encode(stripped.encode()).decode()


def parse_multi(text: str) -> list[dict]:
    """Parse multiple URIs from text (one per line). Skip empty/invalid lines."""
    configs = []
    for line in text.strip().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parsed = parse_uri(line)
        if parsed:
            parsed["_raw_uri"] = line
            configs.append(parsed)
    return configs


# ── VLESS ──────────────────────────────────────────────────────────────────

def _parse_vless(uri: str) -> dict | None:
    try:
        # vless://uuid@host:port?params#name
        without_scheme = uri[len("vless://"):]
        fragment = ""
        if "#" in without_scheme:
            without_scheme, fragment = without_scheme.rsplit("#", 1)
            fragment = unquote(fragment)

        user_host, _, query_str = without_scheme.partition("?")
        uuid, _, host_port = user_host.partition("@")
        host, _, port_str = host_port.rpartition(":")
        port = int(port_str)

        params = _parse_query(query_str)
        security = params.get("security", "")

        base = {
            "protocol": "vless",
            "uuid": uuid,
            "address": host,
            "port": port,
            "name": fragment,
        }

        if security == "reality":
            base.update({
                "security": "reality",
                "sni": params.get("sni", ""),
                "public_key": params.get("pbk", ""),
                "short_id": params.get("sid", ""),
                "fingerprint": params.get("fp", "chrome"),
                "flow": params.get("flow", "xtls-rprx-vision"),
                "transport": params.get("type", "tcp"),
            })
        elif security == "tls":
            base.update({
                "security": "tls",
                "sni": params.get("sni", host),
                "host": params.get("host", host),
                "path": unquote(params.get("path", "/")),
                "transport": params.get("type", "ws"),
                "fingerprint": params.get("fp", ""),
                "alpn": params.get("alpn", ""),
                "allow_insecure": params.get("allowInsecure", "false") == "true",
            })
        else:
            base.update({
                "security": security or "none",
                "sni": params.get("sni", ""),
                "transport": params.get("type", "tcp"),
                "fingerprint": params.get("fp", ""),
                "alpn": params.get("alpn", ""),
                "host": params.get("host", ""),
                "path": unquote(params.get("path", "/")),
            })

        # Parse fragment param: "length,interval,packets" e.g. "10-20,10-20,tlshello"
        frag_str = params.get("fragment", "")
        if frag_str:
            parts = frag_str.split(",")
            if len(parts) == 3:
                base["fragment_length"] = parts[0]
                base["fragment_interval"] = parts[1]
                base["fragment_packets"] = parts[2]

        return base
    except Exception:
        return None


def _build_vless(config: dict) -> str:
    uuid = config["uuid"]
    host = config["address"]
    port = config["port"]
    name = quote(config.get("name", ""), safe="")
    security = config.get("security", "tls")

    if security == "reality":
        params = (
            f"security=reality&encryption=none"
            f"&pbk={config.get('public_key', '')}"
            f"&headerType=none"
            f"&fp={config.get('fingerprint', 'chrome')}"
            f"&type={config.get('transport', 'tcp')}"
            f"&flow={config.get('flow', 'xtls-rprx-vision')}"
            f"&sni={config.get('sni', '')}"
            f"&sid={config.get('short_id', '')}"
        )
    else:
        encoded_path = quote(config.get("path", "/"), safe="")
        params = (
            f"encryption=none&security=tls&type={config.get('transport', 'ws')}"
            f"&host={config.get('host', host)}"
            f"&sni={config.get('sni', host)}"
            f"&path={encoded_path}"
        )
        if config.get("fingerprint"):
            params += f"&fp={config['fingerprint']}"
        if config.get("alpn"):
            params += f"&alpn={config['alpn']}"

    # Fragment: "length,interval,packets"
    if config.get("fragment_length"):
        frag = (
            f"{config['fragment_length']},"
            f"{config.get('fragment_interval', '10-20')},"
            f"{config.get('fragment_packets', 'tlshello')}"
        )
        params += f"&fragment={frag}"

    return f"vless://{uuid}@{host}:{port}?{params}#{name}"


# ── VMess ──────────────────────────────────────────────────────────────────

def _parse_vmess(uri: str) -> dict | None:
    try:
        encoded = uri[len("vmess://"):]
        # Strip fragment if present after base64
        if "#" in encoded:
            encoded = encoded.split("#")[0]
        decoded = _b64_decode(encoded)
        obj = json.loads(decoded)

        return {
            "protocol": "vmess",
            "uuid": obj.get("id", ""),
            "address": obj.get("add", ""),
            "port": int(obj.get("port", 443)),
            "alter_id": int(obj.get("aid", 0)),
            "security": obj.get("scy", "auto"),
            "transport": obj.get("net", "ws"),
            "tls": obj.get("tls", ""),
            "sni": obj.get("sni", ""),
            "host": obj.get("host", ""),
            "path": obj.get("path", "/"),
            "fingerprint": obj.get("fp", ""),
            "alpn": obj.get("alpn", ""),
            "name": obj.get("ps", ""),
        }
    except Exception:
        return None


def _build_vmess(config: dict) -> str:
    obj = {
        "v": "2",
        "ps": config.get("name", ""),
        "add": config["address"],
        "port": str(config["port"]),
        "id": config["uuid"],
        "aid": str(config.get("alter_id", 0)),
        "scy": config.get("security", "auto"),
        "net": config.get("transport", "ws"),
        "type": "none",
        "host": config.get("host", ""),
        "path": config.get("path", "/"),
        "tls": config.get("tls", "tls"),
        "sni": config.get("sni", ""),
        "alpn": config.get("alpn", ""),
        "fp": config.get("fingerprint", ""),
    }
    encoded = base64.b64encode(json.dumps(obj).encode()).decode()
    return f"vmess://{encoded}"


# ── Trojan ─────────────────────────────────────────────────────────────────

def _parse_trojan(uri: str) -> dict | None:
    try:
        without_scheme = uri[len("trojan://"):]
        fragment = ""
        if "#" in without_scheme:
            without_scheme, fragment = without_scheme.rsplit("#", 1)
            fragment = unquote(fragment)

        user_host, _, query_str = without_scheme.partition("?")
        password, _, host_port = user_host.partition("@")
        password = unquote(password)
        host, _, port_str = host_port.rpartition(":")
        port = int(port_str)

        params = _parse_query(query_str)

        return {
            "protocol": "trojan",
            "password": password,
            "address": host,
            "port": port,
            "sni": params.get("sni", host),
            "transport": params.get("type", "tcp"),
            "host": params.get("host", ""),
            "path": unquote(params.get("path", "")),
            "tls": params.get("security", "tls"),
            "fingerprint": params.get("fp", ""),
            "alpn": params.get("alpn", ""),
            "name": fragment,
        }
    except Exception:
        return None


def _build_trojan(config: dict) -> str:
    password = quote(config["password"], safe="")
    host = config["address"]
    port = config["port"]
    name = quote(config.get("name", ""), safe="")

    parts = []
    if config.get("sni"):
        parts.append(f"sni={config['sni']}")
    if config.get("transport", "tcp") != "tcp":
        parts.append(f"type={config['transport']}")
    if config.get("host"):
        parts.append(f"host={config['host']}")
    if config.get("path"):
        parts.append(f"path={quote(config['path'], safe='')}")
    if config.get("tls", "tls") != "tls":
        parts.append(f"security={config['tls']}")
    if config.get("fingerprint"):
        parts.append(f"fp={config['fingerprint']}")
    if config.get("alpn"):
        parts.append(f"alpn={config['alpn']}")

    query = f"?{'&'.join(parts)}" if parts else ""
    return f"trojan://{password}@{host}:{port}{query}#{name}"


# ── Shadowsocks ────────────────────────────────────────────────────────────

def _parse_shadowsocks(uri: str) -> dict | None:
    try:
        without_scheme = uri[len("ss://"):]
        fragment = ""
        if "#" in without_scheme:
            without_scheme, fragment = without_scheme.rsplit("#", 1)
            fragment = unquote(fragment)

        if "@" in without_scheme:
            # SIP002: ss://base64(method:password)@host:port#name
            user_info, _, host_port = without_scheme.rpartition("@")
            host, _, port_str = host_port.rpartition(":")
            port = int(port_str)

            decoded = _b64_decode(unquote(user_info))
            method, _, password = decoded.partition(":")
        else:
            # Legacy: ss://base64(method:password@host:port)#name
            decoded = _b64_decode(without_scheme)
            user_info, _, host_port = decoded.rpartition("@")
            method, _, password = user_info.partition(":")
            host, _, port_str = host_port.rpartition(":")
            port = int(port_str)

        return {
            "protocol": "shadowsocks",
            "method": method,
            "password": password,
            "address": host,
            "port": port,
            "name": fragment,
        }
    except Exception:
        return None


def _build_shadowsocks(config: dict) -> str:
    user_info = f"{config['method']}:{config['password']}"
    encoded = base64.b64encode(user_info.encode()).decode().rstrip("=")
    host = config["address"]
    port = config["port"]
    name = quote(config.get("name", ""), safe="")
    return f"ss://{encoded}@{host}:{port}#{name}"


# ── Helpers ────────────────────────────────────────────────────────────────

def _parse_query(query: str) -> dict[str, str]:
    """Parse query string into a flat dict."""
    params = {}
    if not query:
        return params
    for param in query.split("&"):
        eq_idx = param.find("=")
        if eq_idx > 0:
            key = param[:eq_idx]
            value = param[eq_idx + 1:]
            params[key] = value
    return params


def _b64_decode(s: str) -> str:
    """Try multiple base64 decode strategies."""
    s = s.strip()
    # Add padding if needed
    for candidate in [s, s + "=", s + "==", s + "==="]:
        for decode_fn in [base64.urlsafe_b64decode, base64.b64decode]:
            try:
                return decode_fn(candidate).decode("utf-8")
            except Exception:
                continue
    # Try as plain text (AEAD-2022 format)
    return unquote(s)
