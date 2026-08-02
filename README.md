# ⬇️ Redouane Install

**حمّل الفيديوهات والصوتيات (MP3) من أكثر من 1000 موقع** — تطبيق أندرويد أصلي + تطبيق ويب، بمحرك [yt-dlp](https://github.com/yt-dlp/yt-dlp) + ffmpeg.

| | تطبيق الأندرويد 📱 | تطبيق الويب 🌐 |
|---|---|---|
| جودة | حتى **4K** + دمج ffmpeg | حتى **1080p** (خفيف للسيرفر) |
| MP3 | ✅ | ✅ |
| التحميل بالخلفية + إشعار | ✅ | — |
| مشاركة مباشرة من التطبيقات | ✅ | — |
| التثبيت | ملف APK | رابط ويب، بلا تثبيت |

## 📦 المحتوى

```
├── android-app/     تطبيق أندرويد (Kotlin + Jetpack Compose)
└── web-app/         تطبيق ويب (Python FastAPI + yt-dlp)
    ├── app/main.py              الـ API: /api/info · /api/download
    ├── app/static/index.html    الواجهة (عربي RTL)
    ├── Dockerfile               جاهز للنشر بضغطة
    └── requirements.txt
```

## 📲 تثبيت تطبيق الأندرويد

حمل آخر نسخة من صفحة [Releases](../../releases):
**[⬇️ RedouaneInstall.apk](../../releases/latest/download/RedouaneInstall.apk)**

فعّل «التثبيت من مصادر غير معروفة» فهاتفك ← افتح الملف ← تثبيت.
التطبيق كيخدم مباشرة من الهاتف ديالك: ألصق الرابط أو شاركو من أي تطبيق (يوتيوب/تيك توك...).

## 🌐 تشغيل تطبيق الويب محلياً

```bash
cd web-app
pip install -r requirements.txt
ffmpeg مطلوب للدمج وMP3: sudo apt install ffmpeg   # Linux/WSL
uvicorn app.main:app --host 0.0.0.0 --port 8000
# حل http://localhost:8000
```

## 🚀 نشر تطبيق الويب مجاناً (Render)

1. ادخل لـ [render.com](https://render.com) ← حساب مجاني
2. **New → Blueprint** ← ربط هاد الريبو ← **Apply** (كيقرا `render.yaml` وكيبني الـ Dockerfile بوحدو)
3. من بعد دقايق غادي yields عندك رابط عام: `https://redouane-install.onrender.com`

> متغيرات البيئة: `APK_URL` = رابط تحميل الـAPK (Render ← Environment).

أو أي استضافة Docker أخرى (Railway / Fly.io / VPS): غير ابني الصورة بالـ Dockerfile الموجود.

## 🔌 الـ API

| Endpoint | الوصف |
|---|---|
| `GET /api/info?url=...` | معلومات الفيديو: العنوان، الصورة، المدة، الجودات المتاحة |
| `GET /api/download?url=...&q=best\|1080\|720\|480\|mp3` | تحميل الملف مباشرة |
| `GET /healthz` | فحص الصحة |

## ⚠️ ملاحظات

- **يوتيوب**: أحياناً كيبلوكي IPs ديال السيرفرات ("Sign in to confirm you're not a bot") — داكشي عادي ومعروف. تطبيق الأندرويد ما كيتأثرش تقريباً حيت كيخدم من IP ديال الهاتف.
- المحتوى الخاص (Private/Login) ما كيدوزش.
- الويب بحكم محدودية الموارد المجانية: فيديوهات طويلة بزاف قد تفشل — التطبيق هو الحل الكامل.
- هاد المشروع أداة تقنية محايدة (بحال المتصفح): **حمّل فقط المحتوى اللي عندك الحق فيه**.

## 📝 رخصة

MIT — شوف [LICENSE](LICENSE).
