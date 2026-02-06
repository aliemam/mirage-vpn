<div dir="rtl">

# راه‌اندازی VMess

‏VMess یک پروتکل پروکسی رمزنگاری‌شده است که توسط پروژه V2Ray توسعه یافته. بسیار پرکاربرد است و کانفیگ‌های رایگان زیادی در کانال‌های تلگرام و سایت‌ها موجود است.

---

## راه‌اندازی خودکار

اگر دامنه و Cloudflare را تنظیم کرده‌اید (مراحل ۱ تا ۴ در [راهنمای VLESS WS](VLESS_WS_SETUP.md))، با یک دستور سرور VMess را راه‌اندازی کنید:

```bash
sudo bash <(curl -fsSL https://raw.githubusercontent.com/aliemam/mirage-vpn/main/scripts/server/setup-cloudflare-ws.sh) --domain yourdomain.com --protocol vmess
```

اسکریپت همان اسکریپت VLESS WS است با پروتکل VMess. برای اضافه کردن کلاینت جدید:

```bash
sudo bash <(curl -fsSL ...) --domain yourdomain.com --protocol vmess --add-client
```

---

## استفاده از کانفیگ‌های آماده

اگر از قبل کانفیگ VMess دارید (از برنامه‌هایی مثل v2rayNG، Hiddify، Nekobox یا از کانال‌های تلگرام)، مستقیماً می‌توانید استفاده کنید.

فرمت URI استاندارد VMess:

```
vmess://BASE64-ENCODED-JSON#Name
```

بخش base64 شامل یک JSON با این فیلدهاست:

```json
{
  "v": "2",
  "ps": "Server Name",
  "add": "server-address",
  "port": "443",
  "id": "uuid",
  "aid": "0",
  "scy": "auto",
  "net": "ws",
  "type": "none",
  "host": "your.domain.com",
  "path": "/path",
  "tls": "tls",
  "sni": "your.domain.com"
}
```

---

## راه‌اندازی سرور VMess

### پیش‌نیازها

- یک **سرور مجازی (VPS)** خارج از کشور
- یک **دامنه** (برای حالت WebSocket+TLS)
- حساب **‏Cloudflare** (اختیاری، برای عبور از CDN)

### مرحله ۱: نصب Xray

```bash
ssh root@YOUR-VPS-IP
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
```

### مرحله ۲: تولید UUID

```bash
xray uuid
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
      "port": 8443,
      "listen": "127.0.0.1",
      "protocol": "vmess",
      "settings": {
        "clients": [
          {
            "id": "YOUR-GENERATED-UUID",
            "alterId": 0
          }
        ]
      },
      "streamSettings": {
        "network": "ws",
        "wsSettings": {
          "path": "/yourpath"
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

### مرحله ۴: تنظیم nginx

مانند [راه‌اندازی VLESS WS](VLESS_WS_SETUP.md) -- همان تنظیمات nginx را استفاده کنید. تنها تفاوت پروتکل `vmess` به جای `vless` در تنظیمات Xray است.

### مرحله ۵: راه‌اندازی

```bash
systemctl enable xray
systemctl start xray
```

---

## افزودن به برنامه

‏URI کانفیگ VMess خود را در فایل زیر قرار دهید:

```
android/app/src/main/assets/protocols/vmess/configs.txt
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/vmess/configs.txt.example \
   android/app/src/main/assets/protocols/vmess/configs.txt
```

هر خط یک URI کانفیگ. خطوطی که با `#` شروع می‌شوند نادیده گرفته می‌شوند.

---

## نکات

- ‏VMess و VLESS هر دو توسط Xray-core پشتیبانی می‌شوند و می‌توانید کانفیگ‌های هر دو پروتکل را همزمان استفاده کنید
- اپلیکیشن تمام کانفیگ‌ها را تست و بهترین را انتخاب می‌کند -- فرقی نمی‌کند VMess باشد یا VLESS
- اگر از Cloudflare CDN استفاده می‌کنید، تنظیمات مشابه VLESS WS است

</div>
