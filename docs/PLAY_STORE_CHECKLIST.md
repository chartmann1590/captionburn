# Play Console Submission Checklist

Things you fill in **manually** in the Google Play Console after the AAB is uploaded.
Copy-paste from this file. Sources for each answer are in `marketing/play-store/play-store-copy.md`, the `docs/privacy.html` page (live at `https://chartmann1590.github.io/captionburn/privacy.html`), and the assets in `marketing/play-store/`.

---

## 1. App content → App access

> Is all functionality available without restrictions? **Yes.**

No login, no region lock, no test account needed.

---

## 2. App content → Ads

> Does your app contain ads? **Yes.**

The app uses Google AdMob banners and interstitials.

---

## 3. App content → Content rating

Run the questionnaire. Answers:

| Section | Answer |
|---|---|
| Category | Reference, News, or Educational (closest fit). Or "Utility, Productivity, Communication, or Other" — pick the latter. |
| Violence | None |
| Sexuality | None |
| Profanity | None |
| Controlled substances | None |
| Gambling | None |
| User-generated content | **No** — the app does not host or share user content. (Captions are generated locally and saved to the user's own gallery — not transmitted to a service.) |
| Mature themes | None |
| Miscellaneous | None |

Expected rating: **Everyone**.

---

## 4. App content → Target audience and content

| Field | Answer |
|---|---|
| Target age group | 13+ |
| Designed for children | **No** |
| Appeal to children | **No** |
| Ads to children | N/A (target is 13+) |

---

## 5. App content → News app

> Is this a news app? **No.**

---

## 6. App content → COVID-19 contact tracing or status app

> **No.**

---

## 7. App content → Data safety

This is the form most reviewers care about. Answers:

### Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** — but only via Google AdMob. Nothing is collected by the developer directly. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (AdMob and ML Kit traffic is HTTPS) |
| Do you provide a way for users to request that their data be deleted? | **Yes — uninstall removes all on-device data; AdMob's data is governed by Google's privacy controls.** Provide privacy policy link below. |

### Data types

For each, mark whether it is **Collected**, **Shared**, **Required vs Optional**, and select **Purposes**.

| Data type | Collected | Shared | Required/Optional | Purposes |
|---|---|---|---|---|
| **Device or other IDs** (Advertising ID) | Collected | Shared (with AdMob/Google) | Required | Advertising or marketing, Fraud prevention/security |
| **Approximate location** (IP-derived, by AdMob) | Collected | Shared (with AdMob/Google) | Required | Advertising or marketing |
| **App interactions** (ad impressions/clicks, by AdMob) | Collected | Shared (with AdMob/Google) | Required | Advertising or marketing, Analytics |

Everything else: **Not collected**. In particular:
- Personal info: **No**
- Financial info: **No**
- Health/fitness: **No**
- Messages, photos, videos, audio (the videos and transcripts the user processes): **No** — they stay on-device.
- Files and docs: **No**
- Calendar/contacts: **No**
- App info and performance (Crash logs, diagnostics): **No** — there is no analytics or crash reporting SDK.

### Privacy policy URL

```
https://chartmann1590.github.io/captionburn/privacy.html
```

---

## 8. App content → Government app

> **No.**

---

## 9. App content → Financial features

> **No.**

---

## 10. App content → Health

> **No.**

---

## 11. Store presence → Main store listing

| Field | Value |
|---|---|
| App name | `CaptionBurn: Video Captions` |
| Short description (80 chars) | `Auto-caption and burn subtitles into videos on-device, with translation.` (75 chars) |
| Full description | Paste from `marketing/play-store/play-store-copy.md` |
| App category | **Video Players & Editors** |
| Tags (up to 5) | video, captions, subtitles, accessibility, transcription |
| Contact email | `charles.h.hartmann1@gmail.com` |
| Website | `https://chartmann1590.github.io/captionburn/` |
| Privacy policy | `https://chartmann1590.github.io/captionburn/privacy.html` |

### Asset map

| Play Console field | File to upload |
|---|---|
| **App icon** (512×512, 32-bit PNG) | `marketing/play-store/icon-512.png` |
| **Feature graphic** (1024×500, JPG/PNG) | `marketing/play-store/feature-graphic-1024x500.png` |
| **Phone screenshots** (at least 2, max 8; 16:9 or 9:16) | `marketing/play-store/00-onboarding.png`, `01-home.png`, `02-editor.png`, `03-style-controls.png`, `04-export.png`, `05-settings.png` |
| **Promo video** (YouTube URL, 30s–2min) | Upload `marketing/play-store/captionburn-promo-vertical-voiceover.mp4` to YouTube as Unlisted, paste the URL |

> Tip: Play Console no longer accepts the standalone TV banner / 7"-tablet / 10"-tablet screenshots as required. Phone screenshots are sufficient for a phone-only app.

---

## 12. Pricing & distribution

| Field | Value |
|---|---|
| Free or paid | **Free** |
| Countries | All countries (or pick a subset; check at least US + UK + your country) |
| Contains ads | **Yes** |
| In-app purchases | **No** |

---

## 13. App signing

When you upload your first AAB, Play Console asks how you want to sign:

> **Use Play App Signing.** Google holds the master signing key; you keep `Key.jks` as your **upload key**. This is the recommended path and what we've configured the CI for.

After enrolling, your CI's signature with `Key.jks` will continue to be accepted as the upload signature, and Google will re-sign with the master key for distribution.

---

## 14. Release tracks

Recommended order:

1. **Internal testing** — invite yourself + 1 friend. One AAB upload, fast review (~1 hour).
2. **Closed testing (alpha)** — once internal looks good, expand to ~20 testers.
3. **Production** — after one good week of alpha feedback.

For each track:
- Upload the AAB from the latest GitHub Release (`app-release.aab`)
- Paste release notes (auto-generated by the workflow — copy from the GitHub Release body)
- Submit for review

---

## 15. Pre-launch report

Auto-runs on every internal/alpha track upload. Nothing to enable. Read the report; act on any flagged accessibility/security/crash issues.

---

## 16. Crash deobfuscation

Each release ships a `mapping-<version>.txt` in the GitHub Release assets. After uploading the AAB, go to:

> Play Console → Your app → Release → App Bundle Explorer → pick the bundle → Mappings → upload `mapping-<version>.txt`

This lets Google deobfuscate any crash stacks in Vitals.

---

## 17. AdMob compliance pre-flight

Before flipping production ad units live in AdMob:

- [ ] App is published (or at minimum, in Closed Testing) on Play
- [ ] AdMob app is linked to the Play Store listing (AdMob console → Apps → your app → App settings → Link to store)
- [ ] Test ad behavior on the Pixel 8 Pro one last time using `adb shell` test device IDs
- [ ] Privacy policy URL is reachable from a fresh browser
- [ ] Family Policy compliance: confirm "Not primarily child-directed" in AdMob's app settings

---

## What's NOT on this list (intentionally)

- **Crash reporting** — none configured. Add Firebase Crashlytics later if needed.
- **In-app updates** — not configured. The app is small; users update via the store.
- **In-app review prompt** — not implemented. Worth adding in v0.2.
- **Multiple languages** — listing is English-only for now. Add localizations after v0.1 ships.
