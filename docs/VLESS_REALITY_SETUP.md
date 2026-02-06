<div dir="rtl">

# راه‌اندازی VLESS REALITY

‏REALITY یک پروتکل جدیدتر است که نیازی به دامنه یا CDN ندارد. ترافیک شما را به عنوان بازدید از یک سایت معتبر (مثل google.com) جا می‌زند. تنظیم آن ساده‌تر از WebSocket+TLS است.

---

## پیش‌نیازها

- یک **سرور مجازی (VPS)** خارج از کشور
- همین! نیازی به دامنه یا Cloudflare نیست

---

## روش خودکار (توصیه‌شده)

### مرحله ۱: تهیه سرور مجازی

هر ارائه‌دهنده VPS خارج از کشور کار می‌کند:
- ‏Hetzner، DigitalOcean، Vultr، Linode و غیره
- حداقل: ۱ هسته CPU، ۵۱۲ مگابایت RAM، هر توزیع لینوکس

### مرحله ۲: اجرای اسکریپت نصب

وارد سرور شوید و اسکریپت زیر را اجرا کنید:

```bash
sudo bash -c "$(curl -fsSL https://gist.githubusercontent.com/mmjahanara/ce54d28051c79bd7dc77d22e13c6e609/raw/v2ray.sh)"
```

این اسکریپت به صورت خودکار:
- ‏Xray-core را نصب می‌کند
- کلیدها و UUID تولید می‌کند
- یک دامنه SNI که از سرور شما قابل دسترس است انتخاب می‌کند
- قوانین مسیریابی تنظیم می‌کند (سایت‌های ایرانی را بایپس و تبلیغات را مسدود می‌کند)
- ‏TCP BBR را برای عملکرد بهتر فعال می‌کند
- ‏URI های VLESS REALITY آماده استفاده تولید می‌کند

### مرحله ۳: تغییر SNI (در صورت نیاز)

اگر SNI فعلی مسدود شد:

```bash
bash /root/set-sni.sh
```

---

<details>
<summary>روش دستی (اگر ترجیح می‌دهید از اسکریپت استفاده نکنید)</summary>

### مرحله ۱: نصب Xray

```bash
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
```

### مرحله ۲: تولید کلیدها

```bash
xray x25519
```

این دستور یک `Private key` و یک `Public key` خروجی می‌دهد. هر دو را ذخیره کنید.

‏UUID بسازید:

```bash
xray uuid
```

و یک Short ID (رشته هگزادسیمال ۱ تا ۱۶ کاراکتری):

```bash
openssl rand -hex 8
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
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "YOUR-GENERATED-UUID",
            "flow": "xtls-rprx-vision",
            "level": 0
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "www.google.com:443",
          "xver": 0,
          "serverNames": [
            "www.google.com",
            "google.com"
          ],
          "privateKey": "YOUR-PRIVATE-KEY",
          "shortIds": [
            "YOUR-SHORT-ID"
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
vless://YOUR-UUID@YOUR-SERVER-IP:443?security=reality&encryption=none&pbk=YOUR-PUBLIC-KEY&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.google.com&sid=YOUR-SHORT-ID#MyReality
```

</details>

---

## افزودن به برنامه

‏URI کانفیگ خود را در فایل زیر قرار دهید:

```
android/app/src/main/assets/protocols/vless/configs.txt
```

اگر فایل وجود ندارد، از فایل نمونه کپی کنید:

```bash
cp android/app/src/main/assets/protocols/vless/configs.txt.example \
   android/app/src/main/assets/protocols/vless/configs.txt
```

هر خط یک URI کانفیگ. خطوطی که با `#` شروع می‌شوند نادیده گرفته می‌شوند.

---

## رفع مشکل

- **سرور قابل دسترس نیست**: بررسی کنید Xray اجرا می‌شود (`systemctl status xray`) و پورت ۴۴۳ باز است
- **کانفیگ REALITY کار نمی‌کند**: بررسی کنید Public Key در URI کلاینت با Private Key روی سرور مطابقت دارد. بررسی کنید `serverNames` در تنظیمات سرور شامل SNI مورد استفاده شماست
- **‏SNI مسدود شده**: اسکریپت `set-sni.sh` را اجرا کنید یا SNI را به یک سایت دیگر تغییر دهید

</div>
