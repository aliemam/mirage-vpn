<div dir="rtl">

# راه‌اندازی Trojan

‏Trojan یک پروتکل پروکسی است که ترافیک را مثل HTTPS عادی نشان می‌دهد. برخلاف VLESS و VMess، Trojan از رمز عبور ساده به جای UUID استفاده می‌کند.

---

## استفاده از کانفیگ‌های آماده

اگر از قبل کانفیگ Trojan دارید (از برنامه‌های دیگر یا کانال‌های تلگرام)، مستقیماً استفاده کنید.

فرمت URI استاندارد:

```
trojan://PASSWORD@SERVER:PORT?security=tls&sni=DOMAIN&type=tcp#Name
```

---

## راه‌اندازی سرور Trojan

### روش خودکار (توصیه‌شده)

ابتدا مطمئن شوید DNS دامنه شما (رکورد A) به IP سرور اشاره می‌کند. سپس:

```bash
sudo bash <(curl -fsSL https://raw.githubusercontent.com/aliemam/mirage-vpn/main/scripts/server/setup-trojan.sh) --domain yourdomain.com
```

اسکریپت Xray و certbot نصب می‌کند، گواهی TLS دریافت می‌کند، رمز عبور تولید می‌کند و URI نهایی را نمایش می‌دهد. گواهی به صورت خودکار تمدید می‌شود.

برای اضافه کردن کلاینت جدید:

```bash
sudo bash <(curl -fsSL ...) --domain yourdomain.com --add-client
```

### روش دستی

#### پیش‌نیازها

- یک **سرور مجازی (VPS)** خارج از کشور
- یک **دامنه** با گواهی TLS

### مرحله ۱: نصب Xray

```bash
ssh root@YOUR-VPS-IP
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
```

### مرحله ۲: دریافت گواهی TLS

```bash
apt install -y certbot
certbot certonly --standalone -d yourdomain.com
```

### مرحله ۳: پیکربندی Xray

فایل `/usr/local/etc/xray/config.json` را ویرایش کنید:

```json
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "port": 443,
      "protocol": "trojan",
      "settings": {
        "clients": [
          {
            "password": "YOUR-STRONG-PASSWORD"
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "tls",
        "tlsSettings": {
          "certificates": [
            {
              "certificateFile": "/etc/letsencrypt/live/yourdomain.com/fullchain.pem",
              "keyFile": "/etc/letsencrypt/live/yourdomain.com/privkey.pem"
            }
          ]
        }
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "settings": {}
    }
  ]
}
```

### مرحله ۴: راه‌اندازی

```bash
systemctl enable xray
systemctl start xray
```

### مرحله ۵: URI کانفیگ شما

```
trojan://YOUR-STRONG-PASSWORD@yourdomain.com:443?security=tls&sni=yourdomain.com&type=tcp#MyTrojan
```

---

## افزودن به برنامه

‏URI کانفیگ Trojan خود را در فایل زیر قرار دهید:

```
android/app/src/main/assets/protocols/trojan/configs.txt
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/trojan/configs.txt.example \
   android/app/src/main/assets/protocols/trojan/configs.txt
```

هر خط یک URI کانفیگ. خطوطی که با `#` شروع می‌شوند نادیده گرفته می‌شوند.

---

## نکات

- رمز عبور Trojan باید قوی و طولانی باشد
- ‏Trojan نیاز به گواهی TLS معتبر دارد (برخلاف VLESS REALITY)
- اگر از Cloudflare CDN استفاده می‌کنید، نیازی به گواهی TLS روی سرور نیست (مانند تنظیمات VLESS WS)
- می‌توانید کانفیگ‌های Trojan را همراه با سایر پروتکل‌ها استفاده کنید -- اپلیکیشن بهترین را انتخاب می‌کند

</div>
