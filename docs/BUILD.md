<div dir="rtl">

# ساخت و توزیع APK

این راهنما نحوه ساخت فایل APK، امضای آن و توزیع بین کاربران را توضیح می‌دهد.

---

## ساخت APK

### روش ۱: Docker (توصیه‌شده)

ساده‌ترین روش. ‏Docker تمام وابستگی‌ها (JDK، Android SDK، NDK) را خودکار مدیریت می‌کند.

```bash
docker build -f Dockerfile.build -t mirage-builder .
docker run --rm -v $(pwd)/output:/output mirage-builder
```

فایل APK در مسیر `output/MirageVPN.apk` خواهد بود.

اولین ساخت کمی طول می‌کشد چون Docker باید Android SDK و وابستگی‌ها را دانلود کند. ساخت‌های بعدی از کش Docker استفاده می‌کنند و سریع‌تر هستند.

### روش ۲: Android Studio

۱. پوشه `android/` را در Android Studio باز کنید
۲. صبر کنید تا Gradle sync کامل شود
۳. ‏Build > Generate Signed Bundle / APK
۴. ‏APK را انتخاب و مراحل امضا را دنبال کنید

### روش ۳: خط فرمان

**پیش‌نیازها:**
- ‏JDK 17 یا جدیدتر
- ‏Android SDK با API 34
- ‏Android NDK 26.1.10909125
- ‏Android Build Tools 34.0.0

```bash
# تنظیم متغیرهای محیطی
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125

# ساخت local.properties اگر وجود ندارد
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# ساخت
cd android
./gradlew assembleRelease
```

فایل APK در مسیر زیر خواهد بود:

```
android/app/build/outputs/apk/release/app-release.apk
```

---

## امضای APK

### توسعه و تست

به صورت پیش‌فرض، ساخت از keystore دیباگ اندروید (`~/.android/debug.keystore`) استفاده می‌کند. این برای موارد زیر مناسب است:
- تست شخصی
- اشتراک‌گذاری با گروه کوچک
- ساخت‌های توسعه

### تولید (Production)

برای انتشار واقعی (مخصوصاً اگر قصد توزیع آپدیت دارید)، باید با keystore خودتان امضا کنید. اندروید اجازه نمی‌دهد آپدیت‌ها با کلید متفاوت امضا شوند.

**ساخت keystore:**

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key
```

از شما رمز عبور و اطلاعات هویتی خواسته می‌شود.

**تنظیم ساخت برای استفاده از keystore:**

فایل `android/keystore.properties` بسازید:

```properties
storeFile=/absolute/path/to/my-release-key.jks
storePassword=your-store-password
keyAlias=my-key
keyPassword=your-key-password
```

سپس بلوک `signingConfigs` در فایل `android/app/build.gradle.kts` را آپدیت کنید:

```kotlin
signingConfigs {
    create("release") {
        val props = java.util.Properties()
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            props.load(propsFile.inputStream())
            storeFile = file(props["storeFile"] as String)
            storePassword = props["storePassword"] as String
            keyAlias = props["keyAlias"] as String
            keyPassword = props["keyPassword"] as String
        }
    }
}
```

**مهم:** از فایل keystore و رمزهای عبور نسخه پشتیبان بگیرید. اگر آن‌ها را گم کنید، نمی‌توانید به کاربرانی که نسخه قبلی را نصب کرده‌اند آپدیت بدهید.

---

## توزیع

‏MirageVPN برای sideloading طراحی شده -- نیازی به اپ استور نیست.

### روش‌های اشتراک‌گذاری APK

- **بلوتوث**: ارسال مستقیم فایل APK بین گوشی‌ها
- **پیام‌رسان‌ها**: اشتراک از طریق تلگرام، واتس‌اپ و غیره (بعضی ممکن است `.apk` را مسدود کنند -- در صورت نیاز به `.apk.zip` تغییر نام دهید)
- **کارت حافظه / USB**: کپی فیزیکی فایل
- **لینک مستقیم**: میزبانی APK روی هر وب‌سرور
- **‏QR کد**: تولید QR کد به لینک دانلود

### نصب روی اندروید

کاربران باید:

۱. به **تنظیمات > امنیت** (یا **تنظیمات > برنامه‌ها > دسترسی ویژه**) بروند
۲. **نصب از منابع ناشناس** را فعال کنند
۳. فایل APK را باز کنند و **نصب** را بزنند

### صفحه دانلود ساده

می‌توانید یک صفحه HTML ساده برای جامعه خود بسازید:

```html
<html>
<body>
  <h1>Download VPN</h1>
  <a href="MirageVPN.apk">Download APK</a>
  <p>Version 1.0</p>
</body>
</html>
```

روی هر وب‌سرور، GitHub Pages یا Cloudflare Pages میزبانی کنید.

---

## رفع مشکل ساخت

- **خطای Docker**: مطمئن شوید فایل‌های نمونه کانفیگ را قبل از ساخت کپی کرده‌اید
- **خطای Gradle**: بررسی کنید JDK 17+ نصب است و `ANDROID_HOME` تنظیم شده
- **فضای دیسک**: ساخت Docker حدود ۵ گیگابایت فضا نیاز دارد

</div>
