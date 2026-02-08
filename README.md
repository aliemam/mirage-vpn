<div dir="rtl">

# MirageVPN

**جعبه‌ابزار متن‌باز دور زدن سانسور اینترنت**

## ‏MirageVPN چیست؟

‏MirageVPN یک جعبه‌ابزار متن‌باز اپلیکیشن VPN اندروید است که افراد فنی می‌توانند آن را شخصی‌سازی، ساخت و توزیع کنند تا آزادی اینترنت را برای جامعه خود فراهم کنند. این اپلیکیشن از چندین پروتکل پروکسی برای مبهم‌سازی ترافیک استفاده می‌کند و اتصالات سانسورشده را از ترافیک عادی وب غیرقابل تشخیص می‌سازد.

ریپازیتوری را کلون کنید، کانفیگ‌های سرور خود را اضافه کنید، APK بسازید و آن را به افرادی که نیاز دارند بدهید.

## چرا MirageVPN؟

این پروژه برای ایرانیان خارج از کشور ساخته شده که می‌خواهند مستقیماً به عزیزانشان در ایران کمک کنند. به جای تکیه بر VPN های عمومی که به راحتی شناسایی و مسدود می‌شوند، شما اپلیکیشن اختصاصی خودتان را می‌سازید با کانفیگ‌های شخصی‌سازی‌شده و آن را به خانواده و دوستانتان می‌دهید.

چون کانفیگ‌ها شخصی هستند و بین میلیون‌ها نفر به اشتراک گذاشته نمی‌شوند، شناسایی و مسدود کردن آن‌ها توسط سیستم‌های بازرسی عمیق بسته (DPI) بسیار دشوارتر است.

البته در صورت قطعی کامل اینترنت هیچ ابزاری کار نمی‌کند. اما با MirageVPN می‌توانید از قبل آماده باشید -- تمام پروتکل‌هایی که فکر می‌کنید ممکن است کار کنند را تنظیم کنید، کانفیگ‌هایی که از منابع مورد اعتماد جمع‌آوری کرده‌اید را اضافه کنید و یک اپلیکیشن آماده به عزیزانتان بدهید. وقتی اینترنت برگردد، آن‌ها فقط یک دکمه می‌زنند.

## چگونه کار می‌کند؟

‏MirageVPN ترافیک VPN را به عنوان اتصالات عادی HTTPS از طریق CDN کلادفلر (حالت WebSocket+TLS) پنهان می‌کند، یا از پروتکل REALITY برای تقلید TLS handshake با سایت‌های معتبر استفاده می‌کند. سیستم‌های بازرسی عمیق بسته (DPI) به جای تونل VPN، ترافیک وب عادی می‌بینند.

برای جزئیات فنی به [ARCHITECTURE.md](ARCHITECTURE.md) مراجعه کنید (انگلیسی).

## پروتکل‌های پشتیبانی‌شده

<div dir="ltr">

| Protocol | Description |
|----------|-------------|
| **VLESS WebSocket+TLS** | Via Cloudflare CDN -- most censorship-resistant |
| **VLESS REALITY** | Direct connection -- no domain needed |
| **VMess** | Widely used, many free configs available |
| **Trojan** | Looks like normal HTTPS |
| **Shadowsocks** | Lightweight and fast |
| **DNS Tunnel** | Data over DNS queries -- hardest to block |

</div>

می‌توانید کانفیگ‌های پروتکل‌های مختلف را همزمان استفاده کنید. اپلیکیشن همه را تست و بهترین را انتخاب می‌کند.

## ویژگی‌ها

- **چند پروتکله**: پشتیبانی از VLESS، VMess، Trojan، Shadowsocks و تونل DNS
- **تشخیص خودکار**: اپلیکیشن بر اساس کانفیگ‌های موجود تصمیم می‌گیرد کدام حالت را استفاده کند
- **‏Hot-swap**: تعویض کانفیگ بدون قطعی -- اتصال فعال می‌ماند
- **امتیازدهی هوشمند**: یاد می‌گیرد کدام سرورها بهتر کار می‌کنند
- **بهینه‌سازی پس‌زمینه**: خودکار به کانفیگ سریع‌تر سوئیچ می‌کند
- **ترافیک فریب‌کار**: کوئری‌های DNS جعلی به سایت‌های محبوب ایرانی برای پنهان‌سازی الگوی ترافیک
- **مانیتورینگ سلامت**: بررسی مداوم با اتصال مجدد خودکار
- **آپدیت ریموت**: ارسال سرورهای جدید بدون ساخت مجدد APK
- **رابط کاربری ساده**: یک دکمه اتصال با پشتیبانی فارسی و انگلیسی

---

## شروع سریع

### ۱. کلون ریپازیتوری

```bash
git clone https://github.com/aliemam/mirage-vpn.git
cd mirage-vpn
```

### ۲. کانفیگ‌های پروکسی را اضافه کنید

فایل‌های نمونه را کپی و ویرایش کنید. برای هر پروتکلی که کانفیگ دارید:

```bash
# VLESS
cp android/app/src/main/assets/protocols/vless/configs.txt.example \
   android/app/src/main/assets/protocols/vless/configs.txt

# VMess
cp android/app/src/main/assets/protocols/vmess/configs.txt.example \
   android/app/src/main/assets/protocols/vmess/configs.txt

# Trojan
cp android/app/src/main/assets/protocols/trojan/configs.txt.example \
   android/app/src/main/assets/protocols/trojan/configs.txt

# Shadowsocks
cp android/app/src/main/assets/protocols/shadowsocks/configs.txt.example \
   android/app/src/main/assets/protocols/shadowsocks/configs.txt
```

‏URI های پروکسی خود را در فایل مربوطه وارد کنید (هر URI در یک خط).

### ۳. تنظیمات اپلیکیشن

```bash
cp android/app/src/main/assets/config.json.example \
   android/app/src/main/assets/config.json
```

فایل `config.json` را ویرایش کنید:

```json
{
  "server_name": "My Community",
  "remote_config_url": "https://example.com"
}
```

<div dir="ltr">

| Field | Description |
|-------|-------------|
| `server_name` | Display name in app. Shows "MirageVPN" if empty |
| `remote_config_url` | Remote config URL. Leave empty if not needed |

</div>

### ۴. ساخت APK

```bash
docker build -f Dockerfile.build -t mirage-builder .
docker run --rm -v $(pwd)/output:/output mirage-builder
```

### ۵. توزیع

فایل `output/MirageVPN.apk` را به کاربران خود بدهید.

---

## مستندات

### راه‌اندازی سرور (به تفکیک پروتکل)

<div dir="ltr">

| Guide | Description |
|-------|-------------|
| [VLESS WebSocket+TLS](docs/VLESS_WS_SETUP.md) | VLESS via Cloudflare CDN |
| [VLESS REALITY](docs/VLESS_REALITY_SETUP.md) | REALITY -- no domain needed |
| [VMess](docs/VMESS_SETUP.md) | VMess setup |
| [Trojan](docs/TROJAN_SETUP.md) | Trojan setup |
| [Shadowsocks](docs/SHADOWSOCKS_SETUP.md) | Shadowsocks setup |
| [DNS Tunnel](docs/DNS_TUNNEL_SETUP.md) | DNS tunneling (fallback mode) |

</div>

### عمومی

<div dir="ltr">

| Guide | Description |
|-------|-------------|
| [Build & Distribute](docs/BUILD.md) | Build APK, sign, and distribute |
| [Remote Config](docs/REMOTE_CONFIG.md) | Remote config hosting |
| [User Guide](docs/USER_GUIDE.md) | End-user guide |
| [Architecture](ARCHITECTURE.md) | Technical design (English) |

</div>

---

## استفاده از کانفیگ‌های موجود

اگر از قبل کانفیگ پروکسی دارید (از برنامه‌هایی مثل v2rayNG، Hiddify، Nekobox یا از کانال‌های تلگرام)، مستقیماً می‌توانید استفاده کنید. فقط URI ها را در فایل پروتکل مربوطه کپی کنید.

پروتکل‌های پشتیبانی‌شده: `vless://`، `vmess://`، `trojan://`، `ss://`

---

## مشارکت

از مشارکت استقبال می‌شود. حوزه‌هایی که بیشترین نیاز وجود دارد:

- تکنیک‌های جدید مبهم‌سازی و ضد شناسایی
- بهبود پروتکل و عملکرد
- بهبود رابط کاربری
- تست روی نسخه‌های مختلف اندروید و شرایط شبکه
- مستندات و ترجمه

### پیش‌نیازهای توسعه

<div dir="ltr">

**Scanner tool** — For working on `tools/scanner/`, set up the Python environment:

```bash
cd tools/scanner
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

See [Scanner README](tools/scanner/README.md) for full documentation.

</div>

## مجوز

این پروژه برای اهداف آموزشی و تحقیقاتی است.

## قدردانی

- ‏[Xray-core](https://github.com/XTLS/Xray-core) -- پیاده‌سازی پروتکل‌های VLESS، VMess، Trojan و Shadowsocks
- ‏[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) -- تبدیل بسته‌های IP به SOCKS5
- ‏[slipstream](https://github.com/octeep/slipstream) -- تونل DNS
- ‏[Claude Code](https://claude.ai/claude-code) -- کمک در توسعه، معماری و مستندسازی پروژه

</div>
