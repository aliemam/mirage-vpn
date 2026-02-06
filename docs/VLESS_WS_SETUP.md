<div dir="rtl">

# راه‌اندازی VLESS WebSocket+TLS (از طریق Cloudflare CDN)

این روش مقاوم‌ترین حالت در برابر سانسور است. ترافیک از طریق CDN کلادفلر عبور می‌کند و برای سیستم‌های فیلترینگ مثل ترافیک عادی HTTPS به نظر می‌رسد.

---

## پیش‌نیازها

- یک **دامنه** (ارزان‌ترین‌ها حدود ۱ دلار در سال)
- یک **سرور مجازی (VPS)** خارج از کشور
- یک حساب **‏Cloudflare** (رایگان)

---

## راه‌اندازی خودکار (بعد از تنظیم Cloudflare)

بعد از انجام مراحل ۱ تا ۴ (خرید دامنه، اتصال به Cloudflare، تهیه VPS، تنظیم DNS)، می‌توانید نصب سمت سرور را با یک دستور انجام دهید:

```bash
sudo bash <(curl -fsSL https://raw.githubusercontent.com/aliemam/mirage-vpn/main/scripts/server/setup-cloudflare-ws.sh) --domain yourdomain.com --protocol vless
```

اسکریپت Xray و nginx را نصب می‌کند، UUID تولید می‌کند، قوانین مسیریابی ایران را اضافه می‌کند و URI نهایی را نمایش می‌دهد.

برای اضافه کردن کلاینت جدید:

```bash
sudo bash <(curl -fsSL ...) --domain yourdomain.com --protocol vless --add-client
```

اگر ترجیح می‌دهید دستی نصب کنید، مراحل زیر را دنبال کنید.

---

## مرحله ۱: خرید دامنه

از هر ثبت‌کننده دامنه‌ای می‌توانید خرید کنید:
- ‏[Porkbun](https://porkbun.com) -- دامنه‌های `.click` حدود ۱ دلار در سال
- ‏[Namecheap](https://namecheap.com) -- دامنه‌های `.xyz` حدود ۱ دلار در سال
- هر ثبت‌کننده دیگری هم کار می‌کند

مثال: `myfreedom.xyz`

## مرحله ۲: اتصال دامنه به Cloudflare

۱. یک حساب رایگان در [Cloudflare](https://cloudflare.com) بسازید
۲. دامنه خود را به Cloudflare اضافه کنید
۳. نِیم‌سرورهای دامنه را در ثبت‌کننده به نِیم‌سرورهای Cloudflare تغییر دهید
۴. صبر کنید تا DNS منتشر شود (معمولاً ۵ تا ۳۰ دقیقه)

## مرحله ۳: تهیه سرور مجازی (VPS)

هر ارائه‌دهنده VPS خارج از کشور کار می‌کند:
- ‏Hetzner، DigitalOcean، Vultr، Linode و غیره
- حداقل: ۱ هسته CPU، ۵۱۲ مگابایت RAM، هر توزیع لینوکس
- آدرس IP سرور را یادداشت کنید

## مرحله ۴: تنظیم DNS در Cloudflare

۱. به بخش DNS Settings در Cloudflare بروید
۲. یک رکورد `A` اضافه کنید: `Name` = `@` (یا یک ساب‌دامنه)، `Content` = آدرس IP سرور
۳. مطمئن شوید پروکسی (ابر نارنجی) **روشن** است -- این ترافیک را از طریق Cloudflare مسیریابی می‌کند

## مرحله ۵: نصب Xray روی سرور

```bash
# وارد سرور شوید
ssh root@YOUR-VPS-IP

# نصب Xray
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
```

## مرحله ۶: پیکربندی Xray

ابتدا یک UUID بسازید:

```bash
xray uuid
```

فایل تنظیمات Xray را در مسیر `/usr/local/etc/xray/config.json` ویرایش کنید:

```json
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "port": 8443,
      "listen": "127.0.0.1",
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "YOUR-GENERATED-UUID",
            "level": 0
          }
        ],
        "decryption": "none"
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

## مرحله ۷: تنظیم Reverse Proxy با nginx

```bash
apt install -y nginx certbot python3-certbot-nginx
```

فایل `/etc/nginx/sites-available/xray` را بسازید:

```nginx
server {
    listen 80;
    server_name myfreedom.xyz;

    location /yourpath {
        proxy_redirect off;
        proxy_pass http://127.0.0.1:8443;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

```bash
ln -s /etc/nginx/sites-available/xray /etc/nginx/sites-enabled/
nginx -t && systemctl restart nginx
```

چون Cloudflare خودش TLS را مدیریت می‌کند (با ابر نارنجی روشن)، نیازی به گواهی TLS روی سرور ندارید.

## مرحله ۸: راه‌اندازی Xray

```bash
systemctl enable xray
systemctl start xray
```

## مرحله ۹: URI کانفیگ شما

‏URI کانفیگ شما به این شکل خواهد بود:

```
vless://YOUR-UUID@CLOUDFLARE-IP:443?encryption=none&security=tls&type=ws&host=myfreedom.xyz&sni=myfreedom.xyz&path=%2Fyourpath#MyServer
```

به جای `CLOUDFLARE-IP` می‌توانید از هر IP لبه‌ای Cloudflare استفاده کنید (مثلاً `104.17.71.206`) یا مستقیماً از دامنه خود. استفاده مستقیم از IP های Cloudflare در صورت فیلتر بودن DNS کمک می‌کند.

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

- **سرور قابل دسترس نیست**: بررسی کنید Xray اجرا می‌شود (`systemctl status xray`) و فایروال اجازه ترافیک را می‌دهد (`ufw status`)
- **اتصال WebSocket کار نمی‌کند**: بررسی کنید nginx درست پروکسی می‌کند و ابر نارنجی Cloudflare برای رکورد DNS شما فعال است
- **خطای TLS**: مطمئن شوید `host` و `sni` در URI با دامنه شما در Cloudflare مطابقت دارند

</div>
