# DNS Setup for Mirage VPN

## Step 1: Buy a Domain

Recommended registrars (cheapest options):
- **Porkbun**: `.click` domains ~$1/year
- **Namecheap**: `.xyz` domains ~$1/year
- **Cloudflare**: Transfer existing domains (at cost)

Example: Let's say you buy `miragefreedom.xyz`

## Step 2: Configure DNS Records

You need to create these DNS records:

### For dnstt tunnel (subdomain: `t`)
```
Type    Name                    Value                       TTL
----    ----                    -----                       ---
A       ns-t                    62.78.179.172               300
NS      t                       ns-t.miragefreedom.xyz.     300
```

### For slipstream tunnel (subdomain: `s`)
```
Type    Name                    Value                       TTL
----    ----                    -----                       ---
A       ns-s                    62.78.179.172               300
NS      s                       ns-s.miragefreedom.xyz.     300
```

## Step 3: Verify DNS Setup

Wait 5-10 minutes for DNS propagation, then test:

```bash
# Test that NS record is set correctly
dig NS t.miragefreedom.xyz

# Should show: ns-t.miragefreedom.xyz

# Test that your server receives queries
dig @62.78.179.172 test.t.miragefreedom.xyz
```

## Step 4: Update Configuration

Edit `.env` file on your Raspberry Pi:

```bash
DNSTT_DOMAIN=t.miragefreedom.xyz
SLIPSTREAM_DOMAIN=s.miragefreedom.xyz
```

Then restart the services:
```bash
cd /path/to/mirage_vpn
docker compose -f server/dnstt/docker-compose.yml down
docker compose -f server/dnstt/docker-compose.yml up -d
```

## How It Works

```
[Client in Iran]                    [Public DNS]                [Your Server]
      |                                  |                           |
      |-- DNS query for                  |                           |
      |   xyz.t.miragefreedom.xyz ------>|                           |
      |                                  |-- Who handles             |
      |                                  |   t.miragefreedom.xyz? -->|
      |                                  |                           |
      |                                  |<-- ns-t.miragefreedom.xyz |
      |                                  |                           |
      |                                  |-- Forward query --------->|
      |                                  |                           |
      |<-- Response (with tunnel data) --|<-- Response --------------|
```

The magic: DNS queries look like normal DNS traffic but contain encrypted tunnel data!

## Cloudflare Setup (if using Cloudflare)

If you're using Cloudflare for DNS:

1. Go to DNS settings
2. Make sure the proxy (orange cloud) is **OFF** for ns-t and ns-s records
3. These must be "DNS only" (gray cloud)

## Privacy Note

Consider enabling Cloudflare's privacy protection (WHOIS guard) to hide your identity as the domain owner.
