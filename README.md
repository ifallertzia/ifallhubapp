<div align="center">

# ifallhub — Android App

**Native Android application for [Ifallhub](https://github.com/ifallertzia/ifallhub) — movies, series aur anime with playback, server rotation, recommendations, watchlist and watch tracker.**

Built with **Capacitor 6** + **FFmpegKitNext** · App name: `ifallhub` · Package: `com.ifallhub.app` · Android 8.0+ (API 26+)

</div>

---

## 🎯 What is this?

This repository is the **Capacitor Android wrapper** for the Ifallhub web app. It ports the complete web frontend (Discover, Search, Watch, My List, Tracker — including the full playback engine) to a native Android package and adds:

- **FFmpegKitNext (`com.arthenica:ffmpeg-kit-next:9.0.0`, full-GPL)** — live H.265/HEVC → H.264 transcoding for compatible native streams, plus DASH/HLS remuxing to a single MKV download. The pinned local Maven bundle is fetched with an SHA-256 check because the original Mobile-FFmpeg/FFmpegKit Maven Central artifacts are retired.
- **Native loopback stream server** — the transcode is served to the WebView `<video>` element as a growing fragmented MP4, so the entire overlay UI (gestures, subtitles, quality menu) keeps working.
- **Capacitor Preferences** — watchlist/ratings/progress survive restarts even when the engine is offline.
- **Built-in engine default** — Android connects to `https://ifallhub.onrender.com` on first launch; Settings can override it for a self-hosted engine. Browser builds stay same-origin.
- **CORS-free networking** — `CapacitorHttp` routes API calls through the native HTTP stack.
- **AI-generated branding** — icon, adaptive icons (all densities), splash screens, Play Store banner.
- **GitHub Actions CI** — APK built automatically on every PR and released on merge to `main`.

## 📲 Feature parity with the web app

Everything from [ifallertzia/ifallhub](https://github.com/ifallertzia/ifallhub) `web/` is included:

| Area | Features |
|------|----------|
| **Discover** | Hero carousel, Netflix-style rails (Trending, Top Rated, Fresh, Most Watched), Continue Watching, "Because you watch on Ifallhub", My List rail |
| **Search** | Live suggestions, source/type/sort filters, genre pages |
| **Details** | IMDb rating ring, credits, episodes list, stream list with sizes/codec badges, personal rating, "More like this" |
| **Watch** | Auto server rotation (Sakshi → Ritu → Siddharth → Ifallhub add-ons → Shreya → Everyone), release/mirror picking, visible copyable playback URLs for every format (file, H.265/MKV, DASH/HLS), quality badges, resume position, progress saving, episode prev/next, auto-play next episode |
| **Player** | Play/pause/seek, fullscreen + rotate, gestures (double-tap seek 2x boost, swipe volume/brightness/seek), lock controls, playback speed, quality switching, aspect ratio, PiP, background play toggle, screenshot, clock/battery pill |
| **Subtitles** | Multi-language search, SRT/ASS → WebVTT conversion, language tags |
| **Library** | Watchlist, 1–10 ratings, watch progress — synced to the engine (`/api/library` + SSE), local fallback on device |
| **Tracker** | Hours watched, finished count, episodes tracked, average rating, Continue Watching list, clear-progress actions |
| **Sources** | moviebox (Siddharth), fourkhdhub (Ritu), dramachi (Sakshi), bdix_circleftp (Shreya), bdix_dhakaflix (Everyone), addons (Ifallhub) |
| **Music** | IfallMusic hand-off link |
| **Extras** | Dark theme, bilingual copy (Hindi + English), toasts, offline-tolerant library |

### 🎬 H.265/HEVC — the FFmpegKitNext path

The web player ranks HEVC/.mkv releases last because browsers cannot decode them. On Android the app instead:

1. Detects H.265/HEVC (`_h265_`, `hevc`, `x265`, `hvc1`…) or unsupported containers (`.mkv`, `.avi`…) in the release metadata/URL.
2. Asks the native `FFmpeg` plugin to live-transcode the stream:
   `ffmpeg -i <src> -c:v libx264 -preset ultrafast -tune zerolatency -c:a aac -f mp4 -movflags frag_keyframe+empty_moov …`
3. Serves the growing fragmented MP4 from a loopback HTTP server (`127.0.0.1:<port>/live.mp4`) implemented in `LocalStreamServer.java`.
4. Plays it in the normal `<video>` element — the overlay, subtitles and progress tracking are unchanged.
5. If a direct attempt fails with a codec error mid-rotation, the same transcode path is retried automatically before moving to the next link.

> Real-time transcoding is CPU-heavy. On budget devices prefer 720p/480p releases; the stream list shows a ⚡ badge on transcode-only links.

## 🗂 Project structure

```
ifallhubapp/
├── .github/workflows/
│   ├── build-android.yml      # tests + APK build on PR/push, Release on main
│   └── release.yml            # manual tagged release
├── android/                   # Capacitor Android project
│   └── app/src/main/java/com/ifallhub/app/
│       ├── MainActivity.java      # registers the FFmpeg plugin
│       ├── FFmpegPlugin.java      # FFmpegKitNext bridge (transcode/execute/openExternal)
│       └── LocalStreamServer.java # loopback HTTP progressive streamer
├── src/                       # web app (ported from ifallhub/web, vanilla JS + Vite)
│   ├── lib/                   # api client, formatting, router, library store, recommendations
│   ├── player/                # engine (server rotation), overlay (gestures/controls),
│   │                          # subtitles, title-match, ffmpeg-player (native H.265 bridge)
│   ├── ui/                    # cards, rails, playback links, VLC/external-player button
│   ├── views/                 # home, browse/search, details, watch, mylist, tracker
│   ├── native/ffmpeg.js       # Capacitor bridge to FFmpegPlugin
│   ├── lib/storage.js         # Capacitor Preferences + localStorage adapter
│   └── main.js / state.js / style.css
├── tests/                     # 49 unit tests (engine, format, recommend, subtitles…)
├── assets/                    # AI logo masters (icon, splash, banner) + generated icons
├── scripts/generate-icons.py  # regenerates every mipmap/splash asset
├── index.html                 # app shell (sidebar, search, dialogs incl. Settings)
├── vite.config.js             # build + demo API for offline development
├── capacitor.config.ts        # appId com.ifallhub.app, dark splash, CapacitorHttp
└── package.json
```

## 🚀 Getting started

### Web development (demo data, no engine needed)

```bash
npm install
npm run dev          # http://localhost:5173 with a built-in demo catalog
```

### Point it at a real Ifallhub engine

```bash
# Terminal 1 — the Rust engine (from ifallertzia/ifallhub)
cargo run -- --web-api        # http://127.0.0.1:8787

# Terminal 2 — the web UI
IFALLHUB_API_URL=http://127.0.0.1:8787 npm run dev
```

The Android app connects to `https://ifallhub.onrender.com` automatically on first launch, so you do not need to enter an Engine URL after opening it. To use a different deployment, open **Settings (gear icon)** in the top bar and replace the URL; add an access key if that deployment requires one. Custom settings are stored on the device. Browser deployments continue to use their same-origin API.

### Android build

```bash
npm install
npm run build
npx cap sync android
bash scripts/fetch-ffmpeg-kit-next.sh    # downloads + verifies the pinned native bundle
cd android && ./gradlew assembleDebug    # or assembleRelease
# install: android/app/build/outputs/apk/debug/app-debug.apk
```

Or with the CLI: `npx cap run android` (device/emulator required).

**Requirements:** Node 20+, JDK 17, Android SDK 34. minSdk 26 (Android 8.0).

### Regenerate icons/splash

```bash
python3 scripts/generate-icons.py
```

## ⚙️ Configuration

| Setting | Where | Default |
|---------|-------|---------|
| Engine URL | In-app Settings dialog | `https://ifallhub.onrender.com` |
| Access key | In-app Settings dialog | none |
| Release signing | `ANDROID_KEYSTORE_*` env / repo secrets | debug key fallback |
| App id / name | `capacitor.config.ts` | `com.ifallhub.app` / `ifallhub` |

## 🔨 CI/CD

- **`build-android.yml`** — runs the unit tests, builds the web bundle, syncs Capacitor, builds a signed `ifallhub-release.apk`, uploads it as an artifact (30 days) and — on pushes to `main` — publishes a GitHub Release (`v1.0.<run>`).
- **`release.yml`** — manual `workflow_dispatch` release with a custom version number.
- **Signing:** add repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEYSTORE_ALIAS`, `ANDROID_KEYSTORE_ALIAS_PASSWORD` for production signing. Without them CI generates a throwaway keystore so the APK is still installable.

## 🧪 Testing

```bash
npm test             # 62 tests: engine rotation/scoring, title matching, format,
                     # recommendations, subtitle conversion, playback-link copying
```

## 📝 Notes & limitations

- **Transcode performance** — live H.265 → H.264 costs CPU/battery; the app automatically prefers playable releases first and only transcodes when needed (or when the direct link fails with a codec error).
- **Library SSE** — live library sync over `EventSource` falls back to polling when the engine is cross-origin; everything stays in sync either way.
- **Downloads** — the per-release Download button hands the link to the system downloader/external player via an Android intent (`FFmpegPlugin.openExternal`).
- **Legal** — the app is a client for the user's own configured Ifallhub engine; stream availability depends entirely on the sources that engine is configured with.

## 🙏 Credits

- Web app & engine: [ifallertzia/ifallhub](https://github.com/ifallertzia/ifallhub) (based on MovieBox-TUI)
- Transcoding: [FFmpegKitNext](https://github.com/arthenica/ffmpeg-kit-next) (`com.arthenica:ffmpeg-kit-next:9.0.0`, full-GPL); the pinned binary bundle is built from that upstream source and integrity-checked in `scripts/fetch-ffmpeg-kit-next.sh`.
- DASH/HLS playback: [shaka-player](https://github.com/shaka-project/shaka-player)
- App shell: [Capacitor](https://capacitorjs.com)
