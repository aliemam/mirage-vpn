<div dir="rtl">

# میزبانی کانفیگ از راه دور

کانفیگ‌های ریموت به شما امکان می‌دهد بدون ساخت و توزیع مجدد APK، کانفیگ‌های سرور جدید را به همه کاربران ارسال کنید. وقتی سروری مسدود شود، فایل کانفیگ ریموت را آپدیت می‌کنید و اپلیکیشن تغییرات را خودکار دریافت می‌کند.

---

## ساختار ریموت

اپلیکیشن از ساختار پوشه‌ای زیر برای دریافت کانفیگ استفاده می‌کند:

```
{remote_config_url}/protocols/vless/configs.txt
{remote_config_url}/protocols/vmess/configs.txt
{remote_config_url}/protocols/trojan/configs.txt
{remote_config_url}/protocols/shadowsocks/configs.txt
{remote_config_url}/protocols/dns/config.json
```

**سازگاری با نسخه‌های قبلی:** اگر هیچ‌کدام از مسیرهای بالا کانفیگی برنگرداند، اپلیکیشن URL پایه را به عنوان یک فایل تخت امتحان می‌کند (همه کانفیگ‌ها در یک فایل، بدون ساختار پوشه‌ای).

---

## تنظیم URL ریموت

در فایل `android/app/src/main/assets/config.json` آدرس ریموت را تنظیم کنید:

```json
{
  "server_name": "My Community",
  "remote_config_url": "https://example.com"
}
```

اپلیکیشن این URL را در موارد زیر دریافت می‌کند:
- هر بار اتصال
- هر ۳۰ دقیقه هنگام اتصال (توسط Background Optimizer)

---

## گزینه‌های میزبانی

اپلیکیشن دو حالت دریافت کانفیگ ریموت دارد:

<div dir="ltr">

| Mode | Description | Best for |
|------|-------------|----------|
| **Folder structure** | Separate file per protocol (`/protocols/vless/configs.txt` etc.) | Dedicated server, GitHub Pages |
| **Flat file (fallback)** | All configs in one file | Google Drive, GitHub Gist, Pastebin |

</div>

اپلیکیشن ابتدا ساختار پوشه‌ای را امتحان می‌کند. اگر پاسخی نگرفت، URL پایه را به عنوان فایل تخت دریافت می‌کند.

---

### سرور اختصاصی با nginx (توصیه‌شده)

بهترین گزینه چون از ساختار پوشه‌ای کامل پشتیبانی می‌کند. ریپازیتوری شامل یک فایل Docker Compose برای سرور کانفیگ ساده است.

ساختار پوشه‌ای ریموت:

```bash
# Create folder structure
mkdir -p configs/protocols/vless
mkdir -p configs/protocols/vmess
mkdir -p configs/protocols/trojan
mkdir -p configs/protocols/shadowsocks
mkdir -p configs/protocols/dns

# Add configs
cat > configs/protocols/vless/configs.txt << 'EOF'
vless://uuid@server:443?encryption=none&security=tls&type=ws&host=my.domain&sni=my.domain&path=%2Fpath#Server1
vless://uuid@server2:443?security=reality&encryption=none&pbk=key&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.google.com&sid=abcd#Server2
EOF

cat > configs/protocols/trojan/configs.txt << 'EOF'
trojan://password@server3:443?security=tls&sni=my.domain&type=tcp#Server3
EOF

# DNS settings (optional)
cat > configs/protocols/dns/config.json << 'EOF'
{
  "domains": ["s.yourdomain.com"],
  "resolvers": ["1.1.1.1", "8.8.8.8"]
}
EOF

# Start config server
docker compose -f docker-compose.config-server.yml up -d
```

سرور nginx کانفیگ‌ها را روی پورت 8080 سرو می‌کند. `remote_config_url` را تنظیم کنید:

```
http://YOUR-SERVER-IP:8080
```

اپلیکیشن درخواست‌های زیر را ارسال می‌کند:

```
GET http://YOUR-SERVER-IP:8080/protocols/vless/configs.txt
GET http://YOUR-SERVER-IP:8080/protocols/vmess/configs.txt
GET http://YOUR-SERVER-IP:8080/protocols/trojan/configs.txt
GET http://YOUR-SERVER-IP:8080/protocols/shadowsocks/configs.txt
GET http://YOUR-SERVER-IP:8080/protocols/dns/config.json
```

برای آپدیت کانفیگ‌ها بعداً، فقط فایل‌ها را ویرایش کنید -- نیازی به ریستارت nginx نیست.

### ‏GitHub Pages

اگر سرور اختصاصی ندارید، GitHub Pages گزینه خوبی برای ساختار پوشه‌ای است:

۱. یک ریپازیتوری **private** در GitHub بسازید
۲. ساختار پوشه‌ای را بسازید:
   ```
   protocols/
     vless/configs.txt
     vmess/configs.txt
     trojan/configs.txt
     shadowsocks/configs.txt
     dns/config.json
   ```
۳. ‏GitHub Pages را فعال کنید (Settings > Pages > Source: main branch)
۴. ‏`remote_config_url` را تنظیم کنید:
   ```
   https://USERNAME.github.io/REPO-NAME
   ```

**نکته:** ریپازیتوری private باشد اما GitHub Pages عمومی است. اگر امنیت بیشتری نیاز دارید از سرور اختصاصی استفاده کنید.

---

### ‏Google Drive (فقط فایل تخت)

‏Google Drive از ساختار پوشه‌ای پشتیبانی نمی‌کند. فقط حالت فایل تخت (fallback) کار می‌کند -- یعنی تمام کانفیگ‌های همه پروتکل‌ها در یک فایل:

۱. یک فایل `configs.txt` بسازید و URI های **همه** پروتکل‌ها را در آن بنویسید (هر URI در یک خط)
۲. در Google Drive آپلود کنید
۳. روی فایل راست‌کلیک > **‏Share** > **‏Anyone with the link** can view
۴. لینک اشتراک را کپی کنید:
   ```
   https://drive.google.com/file/d/FILE_ID/view?usp=sharing
   ```
۵. آن را به لینک دانلود مستقیم تبدیل کنید:
   ```
   https://drive.google.com/uc?export=download&id=FILE_ID
   ```
۶. این را به عنوان `remote_config_url` تنظیم کنید

اپلیکیشن ابتدا ساختار پوشه‌ای را امتحان می‌کند (که از Google Drive پاسخ نمی‌گیرد)، سپس خود URL پایه را به عنوان فایل تخت دریافت می‌کند.

**محدودیت:** تنظیمات DNS tunnel از طریق Google Drive قابل ارسال نیست (چون فایل JSON جداگانه نیاز دارد).

### ‏GitHub Gist (فقط فایل تخت)

مشابه Google Drive، فقط حالت فایل تخت کار می‌کند:

۱. به [gist.github.com](https://gist.github.com) بروید
۲. یک **secret** gist بسازید
۳. ‏URI های **همه** پروتکل‌ها را وارد کنید (هر URI در یک خط)
۴. **‏Create secret gist** را بزنید
۵. دکمه **‏Raw** را بزنید
۶. ‏URL خام را کپی کنید (هش کامیت را از URL حذف کنید تا همیشه آخرین نسخه دریافت شود):
   ```
   https://gist.githubusercontent.com/USERNAME/GIST_ID/raw/configs.txt
   ```
۷. این را به عنوان `remote_config_url` تنظیم کنید

**محدودیت:** تنظیمات DNS tunnel از طریق Gist قابل ارسال نیست.

---

### هر میزبان فایل استاتیک

هر سرویسی که فایل‌های استاتیک سرو کند کار می‌کند:

- **با ساختار پوشه‌ای:** ‏Cloudflare Workers، Vercel، Netlify، AWS S3 -- ساختار `/protocols/{protocol}/configs.txt` را بسازید
- **فایل تخت:** ‏Pastebin (URL خام)، هر URL که متن ساده برگرداند

شرایط:
- ‏URL متن ساده برگرداند (نه HTML)
- هر URI در یک خط
- خطوطی که با `#` شروع می‌شوند به عنوان کامنت در نظر گرفته می‌شوند

---

## فرمت فایل کانفیگ

```
# Comments start with #
# Empty lines are ignored

# VLESS WebSocket + TLS
vless://uuid@host:port?encryption=none&security=tls&type=ws&host=domain&sni=domain&path=%2Fpath#Name

# VLESS REALITY
vless://uuid@host:port?security=reality&encryption=none&pbk=key&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=sni.com&sid=shortid#Name

# VMess (base64-encoded JSON)
vmess://eyJhZGQiOiJob3N0IiwicG9ydCI6IjQ0MyIsImlkIjoidXVpZCIsIm5ldCI6IndzIiwidGxzIjoidGxzIiwidjI6IjIifQ==#Name

# Trojan
trojan://password@host:port?security=tls&sni=domain&type=tcp#Name

# Shadowsocks (base64 of method:password)
ss://bWV0aG9kOnBhc3N3b3Jk@host:port#Name
```

---

## نحوه مدیریت کانفیگ توسط اپلیکیشن

- هنگام اتصال، اپلیکیشن URL ریموت را دریافت می‌کند (اگر تنظیم شده باشد)
- کانفیگ‌های ریموت با کانفیگ‌های بسته‌بندی‌شده ادغام می‌شوند
- هر کانفیگ بر اساس موفقیت اتصال، سرعت و آپتایم امتیاز می‌گیرد
- اپلیکیشن ابتدا کانفیگ با بالاترین امتیاز را امتحان می‌کند
- ‏Background Optimizer هر ۵ دقیقه کانفیگ‌های جایگزین را تست می‌کند و اگر کانفیگی ۵۰۰+ میلی‌ثانیه سریع‌تر باشد سوئیچ می‌کند
- کانفیگ‌های ریموت هر ۳۰ دقیقه بازخوانی می‌شوند

## وقتی سرورها مسدود می‌شوند

۱. سرورهای پروکسی جدید راه‌اندازی کنید (یا از ارائه‌دهنده کانفیگ جدید بگیرید)
۲. فایل‌های کانفیگ ریموت را با URI های جدید آپدیت کنید
۳. اپلیکیشن کانفیگ‌های جدید را در اتصال بعدی یا ظرف ۳۰ دقیقه دریافت می‌کند

## کاربران آفلاین

برای کاربرانی که به URL ریموت دسترسی ندارند (مثلاً هنگام قطعی کامل اینترنت):
۱. کانفیگ‌های ریموت قبلاً کش شده (در حافظه دستگاه)
۲. کانفیگ‌های بسته‌بندی‌شده در APK

می‌توانید APK را با کانفیگ‌های بسته‌بندی‌شده جدید بسازید و فیزیکی توزیع کنید (بلوتوث، کارت حافظه و غیره).

</div>
