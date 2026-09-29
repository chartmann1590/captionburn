# Hartmann Studios Dynamic Cross-Promotion Platform

Promote your other Hartmann Studios apps inside each of your apps — **discovered dynamically from your Google Play developer page**, never hardcoded. Publish a new app and every existing app starts promoting it automatically. Unpublish one and it disappears on the next backend refresh.

Components in this repository:

| Component | Location | Runtime |
|---|---|---|
| Backend (discovery + ranking + analytics) | [cloudflare-worker/](cloudflare-worker/) | Cloudflare Worker + KV + D1 |
| Reusable Android SDK | [crosspromo/](crosspromo/) | Kotlin + Compose (Material 3), XML adapter included |
| Reference integration | [app/](app/) (CaptionBurn, settings screen) | Android |

**Live backend:** `https://crosspromo-hartmann.charles-h-hartmann1.workers.dev` (deployed from this repo; see deployment below for a from-scratch setup).

---

## Architecture

```
                      ┌──────────────────────────────────────────────┐
                      │            Cloudflare Worker (free tier)      │
   Android SDK        │                                              │
   (every app)        │  GET  /api/v1/recommendations                │
  ───────────────────►│        1 KV read → ranking → small JSON      │
                      │  POST /api/v1/events   → D1 (rate limited)   │
                      │  GET  /api/v1/catalog, /api/v1/health        │
                      │  /api/v1/admin/*       → ADMIN_TOKEN         │
                      │                                              │
                      │  refresh (cron or self-healing on traffic):  │
                      │    Play developer page ──► package ids       │
                      │    details pages (4-way parallel) ──►        │
                      │    normalized catalog ──► safety validation  │
                      │    ──► KV: current + lastKnownGood           │
                      └──────────────┬───────────────┬───────────────┘
                                     │ KV            │ D1
                              catalog + config   promo_events, app_config
```

Key properties:

- **No hardcoded catalog.** The only input is the developer page URL. Play scraping is fully isolated in [playStoreProvider.ts](cloudflare-worker/src/playStoreProvider.ts) (`PlayStoreCatalogProvider` role) behind a `CatalogSource` interface — if Google changes its HTML, that file and its fixtures are the only thing to update.
- **Clients never touch Google Play.** Android receives a small normalized JSON response and ignores unknown fields (forward compatible).
- **Catalog safety.** A refresh that finds 0 apps, or a sudden >50% drop, is rejected; the last-known-good catalog keeps serving and the rejection is recorded in refresh metadata (see [store.ts](cloudflare-worker/src/store.ts)).
- **No VPS.** Worker + KV + D1 all fit Cloudflare's free tier for this workload.

### Recommendation algorithm (understandable, deterministic — no ML)

Per requested slot:

1. Roll a deterministic hash of `sessionId|placement|sourcePackage:slot:N` → `< popularWeight` (default **0.65**) = **popular** slot, else **random** (exploration) slot.
2. **Popular** slot: weighted random where `weight = popularity² × promotionMultiplier × recencyPenalty × newBoost`.
   - `popularity` (0..1) = weighted mix of log-scaled **installs** (0.50), log-scaled **review count** (0.25), **rating** (0.15) and a **promo-performance** placeholder (0.10, ready for real CTR later). Missing data never penalizes: weights renormalize over what exists, unrated apps get a neutral rating term.
   - `newBoost = newAppBoostMultiplier` (default 4) for apps first discovered within `newAppBoostDays` (default 14) — temporary, never permanent.
   - `recencyPenalty = 0.15` for apps recently shown (from D1 impression counts) — prevents starvation of the tail and over-showing of the head.
3. **Random** slot: uniform over eligible apps — exploration share is protected from the new-app boost.
4. Eligible = enabled ∧ ≠ sourcePackage ∧ ∉ `exclude` (client list + per-app server exclusions). No duplicates per response; result is session-stable within the TTL (default 6h) and rotates across sessions.

The 100,000-selection simulation (`npm run simulate`) verifies: no duplicates, source/disabled never appear, organic exposure strictly follows popularity, manual `promotionMultiplier` wins, new apps out-expose their baseline, exploration ≈ 35%, no app dominates. Sample output:

```
com.hartmann.boosted   18.1%   # manual 2.5x override
com.hartmann.big       17.2%
com.hartmann.mid       15.2%
com.hartmann.newapp    13.9%   # 4x new-app boost for 14 days
com.hartmann.small     13.0%
com.hartmann.tiny      11.7%
com.hartmann.unrated   10.9%
selection mix: popular 56.0% / random 35.1% / new_app_boost 8.9%
```

---

## Backend: Cloudflare setup (exact commands)

```sh
cd cloudflare-worker
npm install

npx wrangler login                       # opens a browser

# One-time resource creation
npx wrangler kv namespace create CROSSPROMO_KV     # copy the id into wrangler.crosspromo.jsonc
npx wrangler d1 create hartmann-crosspromo         # copy the id into wrangler.crosspromo.jsonc

# Schema
npx wrangler d1 migrations apply hartmann-crosspromo --remote -c wrangler.crosspromo.jsonc

# Secret (never in the repo, never in the APK)
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"   # generate
npx wrangler secret put ADMIN_TOKEN -c wrangler.crosspromo.jsonc           # paste it

# Local development
npm run dev                              # wrangler dev on http://localhost:8787

# Deploy
npx wrangler deploy -c wrangler.crosspromo.jsonc

# First catalog population (or wait for cron / first traffic)
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" \
  https://<your-worker>.workers.dev/api/v1/admin/refresh
```

`wrangler.crosspromo.jsonc` declares the KV binding, D1 binding and the cron trigger `0 */6 * * *` (every 6 hours). **Free-plan note:** the Workers free tier allows 5 cron triggers *per account*; if the slots are taken, deploy without the `triggers` block — the worker self-heals anyway: when a recommendation request sees a catalog older than `catalogRefreshIntervalHours`, it refreshes in the background via `waitUntil` without delaying the response. To free cron slots: dash → Workers & Pages → Cron Triggers.

### API

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/v1/catalog` | public | Full normalized catalog (CDN-cached 5 min) |
| `GET /api/v1/recommendations?sourcePackage=&placement=&limit=&sessionId=&exclude=&locale=&sdkVersion=` | public | Validated, rate limited (120/min/IP); serves from KV, never scrapes on request |
| `POST /api/v1/events` | public | Validates against the live catalog (packages must exist, source≠target, field formats/sizes); rate limited (600/min/IP); ≤4 KB bodies |
| `GET /api/v1/health` | public | `{status, catalogApps, lastCatalogRefresh, lastSuccessfulRefresh, cacheStatus}` — no secrets |
| `GET /api/v1/admin/status` | ADMIN_TOKEN | Catalog count, refresh metadata, discovery source, metadata failures, rejected apps, config, CTR summary |
| `GET /api/v1/admin/catalog` | ADMIN_TOKEN | Normalized catalog as stored |
| `POST /api/v1/admin/refresh` | ADMIN_TOKEN | Force a refresh now |
| `GET /api/v1/admin/analytics` | ADMIN_TOKEN | Impressions, clicks, CTR by target / source / placement |
| `POST /api/v1/admin/config` | ADMIN_TOKEN | Remote config: `{"popularWeight":0.65,"randomWeight":0.35,"newAppBoostDays":14,"maxLimit":6,"enabled":false,...}` |
| `POST /api/v1/admin/appconfig` | ADMIN_TOKEN | Per-app config: `{"sourcePackage":"com.x.y","config":{"enabled":true,"maxCards":3,"placements":["settings"],"excludedTargets":["com.a.b"]}}` |

All input is validated (package-id shape, placement `[a-zA-Z0-9_-]{1,40}`, limit clamped to config, exclude ≤20 valid packages, sessionId `[A-Za-z0-9_-]{1,64}`, event body ≤4 KB). D1 queries are parameterized. No user IPs, device IDs or ad IDs are ever stored; `sessionId` is a client-generated random UUID, short-lived by design.

### Remote control (no app releases needed)

- Disable **everything**: `POST /api/v1/admin/config` `{"enabled": false}`.
- Disable **one app**: `POST /api/v1/admin/appconfig` `{"sourcePackage":"com.x.y","config":{"enabled":false}}`.
- Cap cards or restrict placements for one app: same endpoint (`maxCards`, `placements`).
- Never recommend app B inside app A: `{"sourcePackage":"com.a","config":{"excludedTargets":["com.b"]}}`.
- Pause promotion of one catalog app or add a manual boost: `GET /api/v1/admin/catalog`, edit `enabledForPromotion` / `promotionMultiplier`, write back via KV (`catalog:lastKnownGood`). Values are preserved across refreshes.

### Observability

Structured JSON logs (`catalog_refresh_success`, `catalog_refresh_rejected`, `catalog_refresh_error`, `d1_write_failed`, `unhandled_error`) via `wrangler tail -c wrangler.crosspromo.jsonc` or the dashboard. `admin/status` + `admin/analytics` answer: most-impressed/clicked targets, best source apps, best placements, overall CTR, new-app exposure share. Attributed installs arrive as `crosspromo_install` events (see Install Referrer below).

---

## Android SDK integration

The SDK is a normal Gradle module (`:crosspromo`, minSdk 24, Compose M3, Coil, DataStore; Firebase is **compile-only** — hosts without Firebase need nothing).

In `settings.gradle.kts`: `include(":crosspromo")` (already done in this repo).

### 1. Initialize once (Application.onCreate)

```kotlin
// The source package is auto-detected from context.packageName — never configure it.
HartmannCrossPromo.initialize(
    context = applicationContext,
    apiBaseUrl = "https://crosspromo-hartmann.charles-h-hartmann1.workers.dev",
    analytics = /* optional: your PromoAnalytics impl or BackendAnalytics(...) */,
)
```

CaptionBurn does this in [CrossPromoInstaller.kt](app/src/main/java/com/charlesh/captionburn/crosspromo/CrossPromoInstaller.kt), which composes `BackendAnalytics` with a Firebase adapter (logs `crosspromo_impression` / `crosspromo_click` / `crosspromo_install`), and reads the URL from `local.properties` (`crosspromo.url=...`) → `BuildConfig.CROSS_PROMO_URL`. No secrets ship in the APK — the API is public; only admin routes need a token that stays server-side.

### 2. Place the UI (Compose)

```kotlin
// Horizontal carousel, 3 cards, "More from Hartmann Studios" label:
HartmannCrossPromoRow(placement = "settings")

// or a vertical list:
HartmannPromoList(placement = "about")

// or an individual card:
HartmannPromoCard(app = ..., placement = "home", rankPosition = 1, requestId = ...)
```

Behavior baked in: stale-while-revalidate (cached content renders instantly, refresh in background), silent hide when empty/unreachable (never an error state, never blocks the host), honest CTA text ("View app" / "View on Google Play" — nothing that imitates a Google ad), Material 3 light/dark/dynamic color, TalkBack content descriptions ("View <App> on Google Play"), one impression per card per session (not per recomposition), `market://details?id=…` → https Play link → browser fallback, never an APK download.

Recommended placements: settings screen, about screen, bottom of home, end of content lists — never interstitials/popups.

### 3. XML / View-system apps

```xml
<com.hartmann.crosspromo.ui.HartmannPromoRecyclerView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:placement="settings"
    app:maxCards="3" />
```

(requires `androidx.recyclerview:recyclerview` in the host; the widget loads and tracks on attach)

### 4. Install attribution (optional, target apps only)

Google's supported Install Referrer mechanism carries the campaign. The backend builds Play-supported `utm_*` referrers for promoted listings; in a *promoted* app:

```kotlin
HartmannInstallAttribution.trackCrossPromoInstall(this)  // first launch
```

It reads the referrer via `com.android.installreferrer:installreferrer` (no broad permissions), parses `utm_source=<source app>&utm_medium=crosspromo&utm_campaign=hartmann_crosspromo&utm_content=<target>`, and reports one `crosspromo_install` event.

### SDK versioning

`HartmannCrossPromo.SDK_VERSION` ("1.0.0") is sent as `sdkVersion` on every request/event for compatibility debugging. The API is versioned (`/api/v1/`) and the response carries `version` + `configVersion`.

---

## Testing

```sh
# Backend: 56 unit tests (real Play HTML fixtures, parser, safety, ranking, validation)
cd cloudflare-worker && npx vitest run

# 100k-selection recommendation simulation with invariant checks
npm run simulate

# Android: SDK unit tests (parsing, unknown fields, API via MockWebServer, launcher fallback)
./gradlew :crosspromo:testDebugUnitTest

# Host app unit tests
./gradlew :app:testDebugUnitTest

# Types
cd cloudflare-worker && npx tsc --noEmit
```

Parser fixtures under [cloudflare-worker/test/fixtures/](cloudflare-worker/test/fixtures/) are real captured Play pages (developer listing + 4 details pages, rated and unrated). If Google changes markup: save new pages over the fixtures, run `npx vitest run`, and fix only [playStoreProvider.ts](cloudflare-worker/src/playStoreProvider.ts).

---

## Operations cheat-sheet

| Task | How |
|---|---|
| Check health | `curl .../api/v1/health` |
| Inspect catalog | `GET /api/v1/admin/catalog` |
| Force refresh | `POST /api/v1/admin/refresh` (token) |
| See CTR / impressions / clicks | `GET /api/v1/admin/analytics` |
| Tune weights / boost window | `POST /api/v1/admin/config` |
| Kill-switch an app or everything | `POST /api/v1/admin/appconfig` / `config` |
| Watch logs | `npx wrangler tail -c wrangler.crosspromo.jsonc` |
| What if Google changes HTML | Refresh is rejected (0 apps / suspicious drop), last-known-good keeps serving; fix parser against fixtures |
| Add a new app | Nothing to do — next refresh discovers it; it gets a 14-day discovery boost |
| Remove/unpublish an app | Nothing to do — disappears on the next accepted refresh |

## Limitations

- Play shows ratings/review counts only above ~10 ratings; small apps legitimately return `null` there (ranking handles it).
- `installText` is Play's public bucket ("100K+"), so `estimatedMinimumInstalls` is a lower bound, by design.
- Free-plan cron is account-limited; the self-healing refresh keeps the catalog fresh from traffic regardless.
- The promo-performance ranking term is a neutral placeholder until real CTR accumulates; feed it from `admin/analytics` without touching clients.
- Google offers no public authenticated "list my apps" API; the `CatalogSource` abstraction + `GOOGLE_SERVICE_ACCOUNT_JSON` hook exist so an official source can be added ahead of the public page without any client change.
