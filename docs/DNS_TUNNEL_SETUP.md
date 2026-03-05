<div dir="rtl">

# راه‌اندازی تونل DNS

تونل DNS ترافیک VPN را از طریق کوئری‌های DNS ارسال می‌کند. چون DNS برای کار اینترنت ضروری است، مسدود کردن آن بسیار سخت‌تر است.

اپلیکیشن از دو پیاده‌سازی تونل DNS پشتیبانی می‌کند:

- **dnstt** (توصیه‌شده): سریع‌تر و قابل اطمینان‌تر. از microsocks برای SOCKS5 استفاده می‌کند.
- **slipstream** (قدیمی): از QUIC برای مالتی‌پلکس استفاده می‌کند.

وقتی کانفیگ‌های Xray هم موجود باشد، اپلیکیشن همه حالت‌ها (Xray + dnstt + slipstream) را **همزمان** امتحان و از اولین اتصال موفق استفاده می‌کند.

---

## dnstt (توصیه‌شده)

### پیش‌نیازها

- یک **دامنه** که کنترل آن را دارید
- یک **سرور مجازی (VPS)** خارج از کشور

### مرحله ۱: خرید دامنه

ثبت‌کننده‌های توصیه‌شده (ارزان‌ترین‌ها):
- ‏[Porkbun](https://porkbun.com): دامنه‌های `.click` حدود ۱ دلار در سال
- ‏[Namecheap](https://namecheap.com): دامنه‌های `.xyz` حدود ۱ دلار در سال
- ‏[Cloudflare](https://cloudflare.com): انتقال دامنه‌های موجود (بدون سود)

مثال: `yourdomain.com`

### مرحله ۲: تنظیم رکوردهای DNS

در تنظیمات DNS دامنه (مثلاً Cloudflare) این رکوردها را بسازید:

```
Type   Name    Value                    Proxy Status
----   ----    -----                    ------------
A      ns1     YOUR-SERVER-IP           DNS only (gray cloud!)
NS     d1      ns1.yourdomain.com       -
```

**مهم:** رکورد A باید حتماً "DNS only" (ابر خاکستری) باشد، نه Proxied (ابر نارنجی).

اگر چندین سرور دارید، برای هر سرور یک جفت رکورد بسازید:

```
A      ns1     SERVER-1-IP              DNS only
A      ns2     SERVER-2-IP              DNS only
NS     d1      ns1.yourdomain.com       -
NS     d2      ns2.yourdomain.com       -
```

### مرحله ۳: بررسی تنظیمات DNS

۵ تا ۱۰ دقیقه صبر کنید تا DNS منتشر شود، سپس تست کنید:

```bash
# Check NS record
dig NS d1.yourdomain.com

# Should show: ns1.yourdomain.com

# Check that your server receives queries
dig @YOUR-SERVER-IP test.d1.yourdomain.com
```

### مرحله ۴: راه‌اندازی سرور

فایل `server/dnstt/.env` را روی سرور ویرایش کنید:

```bash
DNSTT_DOMAIN=d1.yourdomain.com
```

سرویس را راه‌اندازی کنید:

```bash
cd /path/to/mirage_vpn
docker compose -f server/dnstt/docker-compose.yml up -d
```

اولین بار که سرویس بالا می‌آید، کلید عمومی تولید می‌شود. آن را کپی کنید:

```bash
docker logs dnstt-server 2>&1 | grep "PUBLIC KEY"
```

سپس iptables برای ریدایرکت پورت ۵۳ به ۵۳۰۰ تنظیم کنید:

```bash
iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-port 5300
```

### مرحله ۵: ساخت کانفیگ

فرمت کانفیگ dnstt از URI با طرح `dns://` استفاده می‌کند:

```
dns://BASE64(JSON)
```

محتوای JSON:

```json
{
  "ps": "d1-cf-udp",
  "ns": "d1.yourdomain.com",
  "pubkey": "PUBLIC-KEY-HEX",
  "addr": "1.1.1.1",
  "transport": "udp"
}
```

<div dir="ltr">

| Field | Description |
|-------|-------------|
| `ps` | Config name (for display and scoring) |
| `ns` | NS domain (must match DNS records) |
| `pubkey` | Server's public key (hex, from `server.pub`) |
| `addr` | Resolver address: IP for UDP, URL for DoH |
| `transport` | `udp` or `doh` |

</div>

**ساخت URI:** محتوای JSON را به base64 تبدیل و `dns://` به ابتدا اضافه کنید:

```bash
echo -n '{"ps":"d1-cf-udp","ns":"d1.yourdomain.com","pubkey":"YOUR-PUBKEY","addr":"1.1.1.1","transport":"udp"}' | base64 -w 0
```

سپس `dns://` به ابتدای خروجی اضافه کنید.

### مرحله ۶: افزودن به برنامه

‏URI های dnstt خود را در فایل زیر قرار دهید:

```
android/app/src/main/assets/protocols/dnstt/configs.txt
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/dnstt/configs.txt.example \
   android/app/src/main/assets/protocols/dnstt/configs.txt
```

هر خط یک URI کانفیگ. خطوطی که با `#` شروع می‌شوند نادیده گرفته می‌شوند.

### نکات dnstt

- **Resolver های توصیه‌شده:** Cloudflare (1.1.1.1)، Google (8.8.8.8)، Quad9 (9.9.9.9)، AdGuard (94.140.14.14)
- **حالت DoH:** با استفاده از `https://1.1.1.1/dns-query` به جای `1.1.1.1`، کوئری‌های DNS رمزنگاری می‌شوند
- **چندین کانفیگ بسازید:** ترکیب resolver ها و حالت‌ها (UDP و DoH) تعداد بیشتری مسیر ایجاد می‌کند
- **سرور Docker از microsocks استفاده می‌کند** — پروکسی سبک SOCKS5 که dnstt-server ترافیک را به آن هدایت می‌کند

---

## slipstream (قدیمی)

### پیش‌نیازها

- یک **دامنه** که کنترل آن را دارید
- یک **سرور مجازی (VPS)** خارج از کشور

### تنظیم DNS

```
Type   Name    Value                    TTL
----   ----    -----                   ---
A      ns-s    YOUR-SERVER-IP           300
NS     s       ns-s.yourdomain.com.    300
```

### راه‌اندازی سرور

```bash
cd /path/to/mirage_vpn
docker compose -f server/slipstream/docker-compose.yml up -d
```

### تنظیم در اپلیکیشن

تنظیمات slipstream در فایل جداگانه‌ای ذخیره می‌شود:

```
android/app/src/main/assets/protocols/dns/config.json
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/dns/config.json.example \
   android/app/src/main/assets/protocols/dns/config.json
```

محتوای فایل:

```json
{
  "domains": ["s.yourdomain.com"],
  "resolvers": [
    "1.1.1.1", "1.0.0.1",
    "8.8.8.8", "8.8.4.4",
    "9.9.9.9", "149.112.112.112",
    "208.67.222.222", "208.67.220.220",
    "94.140.14.14", "94.140.15.15",
    "185.228.168.9", "185.228.169.9",
    "8.26.56.26", "8.20.247.20"
  ],
  "listen_port": 5201,
  "use_doh": false,
  "doh_port": 5353,
  "doh_endpoints": [
    "https://cloudflare-dns.com/dns-query",
    "https://dns.google/dns-query",
    "https://dns.quad9.net/dns-query",
    "https://doh.opendns.com/dns-query"
  ]
}
```

<div dir="ltr">

| Field | Description |
|-------|-------------|
| `domains` | DNS tunnel domains. App switches to next if one is blocked |
| `resolvers` | Public DNS resolvers. Defaults are fine |
| `listen_port` | Local tunnel port. Default: `5201` |
| `use_doh` | Use DNS over HTTPS. Default: `false` |
| `doh_port` | DoH proxy port. Default: `5353` |
| `doh_endpoints` | DoH endpoints |

</div>

---

## نحوه کار

```
[Client in Iran]                [Public DNS]                [Your Server]
      |                              |                           |
      |-- DNS query for              |                           |
      |   xyz.d1.yourdomain.com -->  |                           |
      |                              |-- Who is authoritative    |
      |                              |   for d1.yourdomain.com?-->|
      |                              |                           |
      |                              |<-- ns1.yourdomain.com     |
      |                              |                           |
      |                              |-- Forward query --------->|
      |                              |                           |
      |<-- Response (tunnel data) ---|<-- Response --------------|
```

کوئری‌های DNS ظاهراً عادی هستند اما حاوی داده‌های رمزنگاری‌شده تونل هستند.

---

## نکته Cloudflare

اگر از Cloudflare برای مدیریت DNS استفاده می‌کنید:

۱. به تنظیمات DNS بروید
۲. رکوردهای A مربوط به nameserver ها (ns1, ns2, ...) **باید** "DNS only" (ابر خاکستری) باشند
۳. **هرگز** پروکسی Cloudflare (ابر نارنجی) را برای این رکوردها فعال نکنید

## نکته حفظ حریم خصوصی

استفاده از محافظت حریم خصوصی Cloudflare (WHOIS guard) برای مخفی کردن هویت شما به عنوان مالک دامنه توصیه می‌شود.

</div>
