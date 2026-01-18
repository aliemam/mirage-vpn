# Mirage VPN

A DNS tunneling solution to help people bypass internet restrictions.

## Overview

This project provides:
1. **Server components** - Docker-based DNS tunnel servers (dnstt + slipstream)
2. **Android client** - Simple one-button app for non-technical users
3. **Documentation** - User guides in English and Persian

## Architecture

```
[User in Iran]                    [Public DNS]               [Your Server]
   Android                        (Cloudflare)              (Raspberry Pi)
      |                               |                          |
  Mirage VPN  --DNS queries--->  DoH Resolver  --forward--->  DNS Tunnel
      |                               |                          |
      |<--------  Encrypted tunnel data  -------------------|
```

## Quick Start

### 1. Buy a Domain (~$1/year)
Get a cheap domain from Porkbun or Namecheap (.xyz, .click, etc.)

### 2. Set Up DNS Records
See [DNS Setup Guide](docs/DNS_SETUP.md)

### 3. Deploy Server

```bash
# On your Raspberry Pi
git clone <this-repo>
cd mirage_vpn
sudo ./scripts/setup-rasp.sh
```

### 4. Build Android App

```bash
# Install Android Studio or use command line
cd android
./gradlew assembleRelease
```

### 5. Distribute
- Share APK via Bluetooth, SD card, or any available channel
- Include the user guide for non-technical users

## Project Structure

```
mirage_vpn/
├── server/
│   ├── dnstt/           # Immediate solution (works with DarkTunnel app)
│   └── slipstream/      # Better performance solution
├── android/             # Custom Android client
├── scripts/
│   ├── setup-rasp.sh    # Server setup script
│   └── build-android-binaries.sh
└── docs/
    ├── DNS_SETUP.md     # DNS configuration guide
    ├── USER_GUIDE_EN.md # English user guide
    └── USER_GUIDE_FA.md # Persian user guide
```

## Technical Details

### dnstt
- Uses DoH/DoT for encryption
- Compatible with existing DarkTunnel Android app
- Good for immediate deployment

### slipstream
- Uses QUIC over DNS
- 10x faster than dnstt
- Requires custom Android client (included)

## Contributing

This is a humanitarian project. Contributions welcome.

## License

MIT - Use freely to help people access information.

---

**زن، زندگی، آزادی** 🕊️

*Woman, Life, Freedom*
