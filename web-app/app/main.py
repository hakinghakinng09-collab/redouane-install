"""
Redouane Install — Web App
===========================
تطبيق ويب حقيقي لتحميل الفيديوهات من +1000 موقع باستعمال yt-dlp.
GET  /                صفحة الويب
GET  /api/info?url=   معلومات الفيديو (JSON)
GET  /api/download?url=&q=best|1080|720|480|mp3   تحميل الملف مباشرة
GET  /healthz         فحص الصحة (للاستضافة)

متغيرات البيئة:
  APK_URL          رابط تحميل التطبيق ديال الأندرويد
  FFMPEG_LOCATION  (اختياري) مسار ffmpeg إلا ماكانش منصّب فالنظام
"""
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import yt_dlp
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask

APP_DIR = Path(__file__).resolve().parent
STATIC_DIR = APP_DIR / "static"

APK_URL = os.environ.get(
    "APK_URL",
    "https://github.com/hakinghakinng09-collab/redouane-install/releases/latest/download/RedouaneInstall.apk",
)
FFMPEG_LOCATION = os.environ.get("FFMPEG_LOCATION")

# الجودات: فيهم fallback تلقائي إيلا ماكايناش metadata ديال الجودة (فيديوهات مباشرة...)
QUALITY = {
    "best": ["-f", "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", "--merge-output-format", "mp4"],
    "1080": ["-f", "bv*[height<=1080]+ba/b[height<=1080]/b[height<=1080]/b", "--merge-output-format", "mp4"],
    "720": ["-f", "bv*[height<=720]+ba/b[height<=720]/b[height<=720]/b", "--merge-output-format", "mp4"],
    "480": ["-f", "bv*[height<=480]+ba/b[height<=480]/b[height<=480]/b", "--merge-output-format", "mp4"],
    "mp3": ["-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"],
}

app = FastAPI(title="Redouane Install Web", docs_url="/api/docs")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/", response_class=HTMLResponse)
def home() -> HTMLResponse:
    html = (STATIC_DIR / "index.html").read_text(encoding="utf-8")
    return HTMLResponse(html.replace("__APK_URL__", APK_URL))


@app.get("/healthz")
def healthz():
    return {"ok": True, "ts": int(time.time())}


def _check_url(u: str) -> str:
    u = (u or "").strip()
    if not re.match(r"^https?://", u, re.I):
        raise HTTPException(400, "ألصق رابط صحيح كيبدا بـ http")
    return u


def _friendly(msg: str) -> str:
    m = (msg or "").lower()
    if "sign in to confirm" in m or "not a bot" in m:
        return ("يوتيوب كيبلوكي السيرفرات مؤقتاً (تحقق IP مركز البيانات). "
                "جرب موقع آخر، أو استعمل تطبيق الأندرويد — كيخدم من هاتفك مباشرة.")
    if "unsupported url" in m:
        return "هاد الرابط ماشي مدعوم"
    if "private" in m or "login" in m:
        return "المحتوى خاص وكيحتاج تسجيل الدخول"
    if "unable to" in m or "connection" in m or "network" in m:
        return "مشكل في الشبكة أو الرابط — تثبت وجرب مرة أخرى"
    if "timed out" in m or "timeout" in m:
        return "الطلب طول بزاف — جرب مرة أخرى"
    return "وقع خطأ: " + (msg or "غير معروف")[:160]


@app.get("/api/info")
def api_info(url: str = Query(...)):
    url = _check_url(url)
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "socket_timeout": 25,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            data = y.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as e:
        raise HTTPException(400, _friendly(str(e)))
    except Exception as e:
        raise HTTPException(500, _friendly(str(e)))

    if not isinstance(data, dict):
        raise HTTPException(400, "ما نقدرش نجيب معلومات الفيديو")

    heights = sorted(
        {int(f["height"]) for f in (data.get("formats") or []) if f.get("height") and f.get("vcodec") not in (None, "none")},
        reverse=True,
    )
    return JSONResponse({
        "title": data.get("title") or "فيديو",
        "thumbnail": data.get("thumbnail"),
        "duration": data.get("duration"),
        "uploader": data.get("uploader"),
        "site": data.get("extractor_key") or data.get("extractor"),
        "webpage_url": data.get("webpage_url") or url,
        "heights": heights[:8],
    })


@app.get("/api/download")
def api_download(url: str = Query(...), q: str = Query("best")):
    url = _check_url(url)
    if q not in QUALITY:
        raise HTTPException(400, "جودة غير معروفة")

    tmp = Path(tempfile.mkdtemp(prefix="rinst_"))
    out_tpl = str(tmp / "%(title).50B [%(id)s].%(ext)s")

    attempts = [QUALITY[q]]
    # محاولة أخيرة احتياطية بالصيغة الأبسط
    attempts.append(["-f", "b"] if q != "mp3" else
                    ["-f", "b", "-x", "--audio-format", "mp3", "--audio-quality", "0"])

    ff_args = ["--ffmpeg-location", FFMPEG_LOCATION] if FFMPEG_LOCATION else []
    last_err = "unknown"
    proc = None
    for args in attempts:
        cmd = [
            sys.executable, "-m", "yt_dlp", *ff_args,
            "--no-playlist", "--newline", "--no-mtime",
            "--retries", "3",
            "-o", out_tpl, *args, url,
        ]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        except subprocess.TimeoutExpired:
            raise HTTPException(504, "الفيديو كبير بزاف — جرب جودة أخف (480p / MP3)")
        if proc.returncode == 0:
            break
        log = (proc.stderr or proc.stdout or "").strip().splitlines()
        last_err = log[-1] if log else "unknown"

    if proc is None or proc.returncode != 0:
        raise HTTPException(500, _friendly(last_err))

    files = [f for f in tmp.iterdir() if f.is_file() and not f.name.endswith((".part", ".ytdl"))]
    if not files:
        raise HTTPException(500, "ما نقدرش نهز الملف. جرب جودة أخرى.")

    f = max(files, key=lambda x: x.stat().st_mtime)

    def _cleanup():
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    return FileResponse(path=f, filename=f.name, background=BackgroundTask(_cleanup))
