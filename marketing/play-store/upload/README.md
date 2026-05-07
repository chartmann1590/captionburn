# Play Console upload folder

Everything you upload to the Google Play Console for CaptionBurn, organized by section. Numbers match the order you fill them in.

```
upload/
├── 01-listing/                    ← Text fields in "Main store listing"
│   ├── title.txt                  → App name (max 30 chars)
│   ├── short-description.txt      → Short description (max 80 chars)
│   ├── full-description.txt       → Full description (max 4000 chars)
│   └── contact.txt                → Email, website, privacy URL, category, tags, audience
│
├── 02-graphics/                   ← Graphic assets in "Main store listing"
│   ├── app-icon-512x512.png       → Hi-res app icon (512×512, required)
│   ├── feature-graphic-1024x500.png → Feature graphic (1024×500, required)
│   └── promo-video-url.txt        → Where to upload the promo video + paste the YouTube URL
│
├── 03-screenshots/
│   ├── phone/                     → Phone screenshots (Required, min 2, max 8)
│   │   ├── 01-onboarding.png      (1008×2244, real device)
│   │   ├── 02-home.png
│   │   ├── 03-editor.png
│   │   ├── 04-export.png
│   │   └── 05-settings.png
│   │
│   ├── tablet-7-inch/             → 7-inch tablet screenshots (Optional)
│   │   ├── 01-onboarding.png      (1200×1920)
│   │   ├── 02-home.png
│   │   ├── 03-editor.png
│   │   ├── 04-export.png
│   │   └── 05-settings.png
│   │
│   └── tablet-10-inch/            → 10-inch tablet screenshots (Optional)
│       ├── 01-onboarding.png      (1600×2560)
│       ├── 02-home.png
│       ├── 03-editor.png
│       ├── 04-export.png
│       └── 05-settings.png
│
└── 04-promo-video/                ← Source promo video (upload to YouTube, then paste URL in 02-graphics/promo-video-url.txt)
    └── captionburn-promo-vertical-voiceover.mp4
```

## Field-by-field mapping (Play Console UI)

| Play Console field | File to use |
|---|---|
| **Main store listing → App name** | `01-listing/title.txt` |
| **Main store listing → Short description** | `01-listing/short-description.txt` |
| **Main store listing → Full description** | `01-listing/full-description.txt` |
| **Main store listing → App icon** | `02-graphics/app-icon-512x512.png` |
| **Main store listing → Feature graphic** | `02-graphics/feature-graphic-1024x500.png` |
| **Main store listing → Promo video** | YouTube URL from `02-graphics/promo-video-url.txt` (after uploading `04-promo-video/captionburn-promo-vertical-voiceover.mp4` to YouTube) |
| **Main store listing → Phone screenshots** | All 5 PNGs in `03-screenshots/phone/` |
| **Main store listing → 7-inch tablet screenshots** | All 5 PNGs in `03-screenshots/tablet-7-inch/` |
| **Main store listing → 10-inch tablet screenshots** | All 5 PNGs in `03-screenshots/tablet-10-inch/` |
| **Store settings → Category** | `Video Players & Editors` (see `01-listing/contact.txt`) |
| **Store settings → Tags** | `01-listing/contact.txt` |
| **Store settings → Contact email / Website / Privacy** | `01-listing/contact.txt` |

## Notes

- **Phone screenshots** are real device captures from a Pixel 8 Pro running the app.
- **Tablet screenshots** are marketing composites (the same phone screenshot framed inside a brand-styled tablet canvas with a section label). The app does not yet have a tablet-optimized layout; once it does, replace these with native tablet captures.
- The 5 slides cover the full user flow: onboarding → home → editor → export → settings. (The 6-slide set seen elsewhere included a misnamed "style controls" image that was actually the onboarding screen — dropped here.)
- Beyond this folder, `docs/PLAY_STORE_CHECKLIST.md` covers everything the Play Console asks for that *isn't* a file upload (data safety, content rating, target audience, App Signing enrollment, etc.).
