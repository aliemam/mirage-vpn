<div dir="rtl">

# راه‌اندازی Shadowsocks

‏Shadowsocks یک پروتکل پروکسی سبک و سریع است. یکی از قدیمی‌ترین ابزارهای دور زدن سانسور که هنوز فعالانه استفاده می‌شود.

---

## استفاده از کانفیگ‌های آماده

اگر از قبل کانفیگ Shadowsocks دارید، مستقیماً استفاده کنید.

فرمت SIP002 (استاندارد):

```
ss://BASE64(method:password)@SERVER:PORT#Name
```

مثال:

```
ss://YWVzLTI1Ni1nY206WU9VUi1QQVNTV09SRA==@203.0.113.50:8388#MyShadowsocks
```

بخش base64 حاوی `method:password` است. مثلاً `aes-256-gcm:YOUR-PASSWORD`.

---

## راه‌اندازی سرور Shadowsocks

### روش خودکار (توصیه‌شده)

یک دستور روی سرور اجرا کنید:

```bash
sudo bash <(curl -fsSL https://raw.githubusercontent.com/aliemam/mirage-vpn/main/scripts/server/setup-shadowsocks.sh)
```

اسکریپت به صورت خودکار Docker نصب می‌کند، رمز عبور تولید می‌کند و URI نهایی را نمایش می‌دهد.

گزینه‌های اختیاری:

```bash
# پورت و متد دلخواه
sudo bash <(curl -fsSL ...) --port 9999 --method chacha20-ietf-poly1305

# رمز عبور مشخص
sudo bash <(curl -fsSL ...) --password "MySecretPass"
```

### روش‌های دستی

### روش ۱: Shadowsocks-rust (توصیه‌شده)

```bash
ssh root@YOUR-VPS-IP

# نصب با Docker
docker run -d \
  --name ss-server \
  --restart always \
  -p 8388:8388 \
  -p 8388:8388/udp \
  ghcr.io/shadowsocks/ssserver-rust:latest \
  ssserver -s "[::]:8388" -m "aes-256-gcm" -k "YOUR-PASSWORD" -U
```

### روش ۲: Outline Server

‏[Outline](https://getoutline.org/) یک ابزار گرافیکی برای مدیریت سرور Shadowsocks است:

۱. ‏[Outline Manager](https://getoutline.org/get-started/) را روی کامپیوتر خود نصب کنید
۲. یک سرور جدید بسازید (از DigitalOcean، AWS یا سرور خودتان)
۳. کلید دسترسی (Access Key) را کپی کنید -- این همان URI استاندارد Shadowsocks است

### روش ۳: نصب دستی

```bash
# نصب shadowsocks-libev
apt install -y shadowsocks-libev

# ویرایش تنظیمات
cat > /etc/shadowsocks-libev/config.json << 'EOF'
{
    "server": "0.0.0.0",
    "server_port": 8388,
    "password": "YOUR-PASSWORD",
    "method": "aes-256-gcm",
    "timeout": 300,
    "fast_open": true
}
EOF

# راه‌اندازی
systemctl enable shadowsocks-libev
systemctl start shadowsocks-libev
```

---

## ساخت URI کانفیگ

بخش `method:password` را به base64 تبدیل کنید:

```bash
echo -n "aes-256-gcm:YOUR-PASSWORD" | base64
```

خروجی مثلاً: `YWVzLTI1Ni1nY206WU9VUi1QQVNTV09SRA==`

‏URI نهایی:

```
ss://YWVzLTI1Ni1nY206WU9VUi1QQVNTV09SRA==@YOUR-SERVER-IP:8388#MyShadowsocks
```

---

## افزودن به برنامه

‏URI کانفیگ Shadowsocks خود را در فایل زیر قرار دهید:

```
android/app/src/main/assets/protocols/shadowsocks/configs.txt
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/shadowsocks/configs.txt.example \
   android/app/src/main/assets/protocols/shadowsocks/configs.txt
```

هر خط یک URI کانفیگ. خطوطی که با `#` شروع می‌شوند نادیده گرفته می‌شوند.

---

## متدهای رمزنگاری پشتیبانی‌شده

<div dir="ltr">

| Method | Description |
|--------|-------------|
| `aes-256-gcm` | Recommended -- secure and fast |
| `aes-128-gcm` | Faster, less secure |
| `chacha20-ietf-poly1305` | Best for devices without hardware AES |

</div>

---

## نکات

- رمز عبور قوی استفاده کنید
- پورت پیش‌فرض ۸۳۸۸ است اما می‌توانید تغییر دهید
- ‏Shadowsocks معمولاً سبک‌تر و سریع‌تر از VLESS/VMess است اما مقاومت کمتری در برابر DPI دارد
- می‌توانید کانفیگ‌های Shadowsocks را همراه با سایر پروتکل‌ها استفاده کنید

</div>
