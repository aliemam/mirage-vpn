<div dir="rtl">

# راه‌اندازی تونل DNS

تونل DNS ترافیک VPN را از طریق کوئری‌های DNS ارسال می‌کند. چون DNS برای کار اینترنت ضروری است، مسدود کردن آن بسیار سخت‌تر است. این حالت به عنوان پشتیبان استفاده می‌شود -- وقتی حتی VLESS از طریق Cloudflare هم مسدود شود.

**نکته:** اپلیکیشن به صورت خودکار تشخیص می‌دهد چه حالتی استفاده کند. اگر کانفیگ‌های پروکسی (VLESS/VMess/Trojan/Shadowsocks) موجود باشد ابتدا آن‌ها را امتحان می‌کند. فقط در صورت عدم موفقیت، از تونل DNS استفاده می‌شود.

---

## پیش‌نیازها

- یک **دامنه** که کنترل آن را دارید
- یک **سرور مجازی (VPS)** خارج از کشور

---

## مرحله ۱: خرید دامنه

ثبت‌کننده‌های توصیه‌شده (ارزان‌ترین‌ها):
- ‏[Porkbun](https://porkbun.com): دامنه‌های `.click` حدود ۱ دلار در سال
- ‏[Namecheap](https://namecheap.com): دامنه‌های `.xyz` حدود ۱ دلار در سال
- ‏[Cloudflare](https://cloudflare.com): انتقال دامنه‌های موجود (بدون سود)

مثال: `yourdomain.com`

## مرحله ۲: تنظیم رکوردهای DNS

این رکوردها را در تنظیمات DNS دامنه خود بسازید:

### برای تونل dnstt (ساب‌دامنه: `t`)

```
Type   Name                    Value                        TTL
----   ----                    -----                       ---
A      ns-t                    YOUR-SERVER-IP               300
NS     t                       ns-t.yourdomain.com.        300
```

### برای تونل slipstream (ساب‌دامنه: `s`)

```
Type   Name                    Value                        TTL
----   ----                    -----                       ---
A      ns-s                    YOUR-SERVER-IP               300
NS     s                       ns-s.yourdomain.com.        300
```

## مرحله ۳: بررسی تنظیمات DNS

۵ تا ۱۰ دقیقه صبر کنید تا DNS منتشر شود، سپس تست کنید:

```bash
# Check NS record
dig NS t.yourdomain.com

# Should show: ns-t.yourdomain.com

# Check that your server receives queries
dig @YOUR-SERVER-IP test.t.yourdomain.com
```

## مرحله ۴: راه‌اندازی سرور

فایل `.env` را روی سرور ویرایش کنید:

```bash
DNSTT_DOMAIN=t.yourdomain.com
SLIPSTREAM_DOMAIN=s.yourdomain.com
```

سرویس‌ها را راه‌اندازی کنید:

```bash
cd /path/to/mirage_vpn
docker compose -f server/dnstt/docker-compose.yml down
docker compose -f server/dnstt/docker-compose.yml up -d
```

---

## نحوه کار

```
[Client in Iran]                [Public DNS]                [Your Server]
      |                              |                           |
      |-- DNS query for              |                           |
      |   xyz.t.yourdomain.com -->   |                           |
      |                              |-- Who is authoritative    |
      |                              |   for t.yourdomain.com?-->|
      |                              |                           |
      |                              |<-- ns-t.yourdomain.com    |
      |                              |                           |
      |                              |-- Forward query --------->|
      |                              |                           |
      |<-- Response (tunnel data) ---|<-- Response --------------|
```

کوئری‌های DNS ظاهراً عادی هستند اما حاوی داده‌های رمزنگاری‌شده تونل هستند.

---

## تنظیم در اپلیکیشن

تنظیمات تونل DNS در فایل جداگانه‌ای ذخیره می‌شود:

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
  "use_doh": true,
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
| `use_doh` | Use DNS over HTTPS. Default: `true` |
| `doh_port` | DoH proxy port. Default: `5353` |
| `doh_endpoints` | DoH endpoints |

</div>

---

## نکات Cloudflare

اگر از Cloudflare برای مدیریت DNS استفاده می‌کنید:

۱. به تنظیمات DNS بروید
۲. مطمئن شوید پروکسی (ابر نارنجی) برای رکوردهای `ns-t` و `ns-s` **خاموش** است
۳. این رکوردها باید "DNS only" (ابر خاکستری) باشند

## نکته حفظ حریم خصوصی

استفاده از محافظت حریم خصوصی Cloudflare (WHOIS guard) برای مخفی کردن هویت شما به عنوان مالک دامنه توصیه می‌شود.

</div>
