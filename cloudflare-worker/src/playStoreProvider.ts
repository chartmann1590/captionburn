/**
 * PlayStoreCatalogProvider — the ONLY place that knows about Google Play's HTML.
 *
 * Two layers:
 *  - PlayListingScraper: developer page -> package IDs + lightweight listing entries.
 *  - PlayAppDetailsScraper: details page -> normalized metadata for one app.
 *
 * If Google changes its markup, only this file (and its fixtures/tests) should
 * need updating. Every selector strategy has at least one fallback; missing
 * optional fields are tolerated per-app and never fail the whole catalog.
 */
import type { CatalogApp, CatalogSource, DiscoveredCatalog } from './types';
import { attr, decodeHtmlEntities, looksLikeHtmlDocument, textOf } from './html';

void attr;

export const PLAY_BASE = 'https://play.google.com';
const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

export const PACKAGE_ID_PATTERN = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$/i;

/** Validate a package id: reverse-domain shape, sane length, no sneaky characters. */
export function isValidPackageId(pkg: unknown): pkg is string {
  return (
    typeof pkg === 'string' &&
    pkg.length >= 3 &&
    pkg.length <= 100 &&
    PACKAGE_ID_PATTERN.test(pkg)
  );
}

/** Normalize a Play details URL to its bare package id, rejecting unrelated links. */
export function extractPackageFromUrl(href: string): string | null {
  if (!href) return null;
  let path = href;
  if (/^https?:\/\//i.test(href)) {
    try {
      const u = new URL(href);
      if (u.hostname !== 'play.google.com') return null;
      path = u.pathname + u.search;
    } catch {
      return null;
    }
  }
  const m = path.match(/[?&]id=([A-Za-z0-9._]+)/);
  if (!m) return null;
  const pkg = m[1];
  return isValidPackageId(pkg) ? pkg : null;
}

function toStoreUrl(pkg: string): string {
  return `${PLAY_BASE}/store/apps/details?id=${encodeURIComponent(pkg)}`;
}

export interface ListingEntry {
  packageName: string;
  name: string | null;
  iconUrl: string | null;
  developer: string | null;
}

/**
 * Parse the developer/publication listing page into listing entries.
 *
 * Primary strategy: anchors whose href is a /store/apps/details?id=... link inside
 * a card that also carries the listing title span (class DdYX5 today) and developer
 * span (class wMUdtb today). We do NOT trust any single CSS class for package
 * discovery — the href itself is canonical — the classes only decorate it with
 * name/developer. Anything that fails validation is reported in `rejected`.
 */
export function parseDeveloperListing(html: string, expectedDeveloper: string): {
  entries: ListingEntry[];
  rejected: { packageName: string; reason: string }[];
} {
  const entries = new Map<string, ListingEntry>();
  const rejected: { packageName: string; reason: string }[] = [];

  const anchorRe = /<a\s[^>]*href="([^"]*)"[^>]*>/gi;
  let match: RegExpExecArray | null;
  while ((match = anchorRe.exec(html)) !== null) {
    const anchorTag = match[0];
    const href = match[1];
    const pkg = extractPackageFromUrl(href);
    if (!pkg) continue;

    // Restrict to the details-page fragment of the current document only.
    // The anchor for an app card ends with </a>. Video-style cards embed a large
    // thumbnail block before the title spans, so allow a generous window; the
    // first </a> closes this card's anchor and keeps the scope tight.
    const cardScope = html.slice(match.index, match.index + 8000);
    const endA = cardScope.indexOf('</a>');
    const cardHtml = endA > 0 ? cardScope.slice(0, endA) : cardScope;

    const name = extractSpanText(cardHtml, 'DdYX5') ?? extractSpanText(cardHtml, 'notranslate');
    const developer = extractSpanText(cardHtml, 'wMUdtb');

    if (developer && !developerIncludes(developer, expectedDeveloper)) {
      rejected.push({ packageName: pkg, reason: `developer mismatch: ${developer}` });
      continue;
    }

    const iconUrl = extractFirstIcon(cardHtml);
    const existing = entries.get(pkg);
    if (!existing) {
      entries.set(pkg, { packageName: pkg, name, iconUrl, developer: developer ?? null });
    } else {
      // Normalize duplicate links: first non-null wins per field.
      entries.set(pkg, {
        packageName: pkg,
        name: existing.name ?? name,
        iconUrl: existing.iconUrl ?? iconUrl,
        developer: existing.developer ?? developer ?? null,
      });
    }
  }

  // Dedup/reject pass: enforce package validation once more.
  const out: ListingEntry[] = [];
  for (const entry of entries.values()) {
    if (!isValidPackageId(entry.packageName)) {
      rejected.push({ packageName: entry.packageName, reason: 'invalid package id' });
      continue;
    }
    out.push(entry);
  }
  return { entries: out, rejected };
}

function developerIncludes(developer: string, expected: string): boolean {
  const norm = (s: string) => s.replace(/\s+/g, ' ').trim().toLowerCase();
  return norm(developer).includes(norm(expected));
}

/** Find the text of a <span class="...CLASS...">…</span> in a fragment. */
function extractSpanText(fragment: string, classHint: string): string | null {
  const re = new RegExp(`<span[^>]*class="[^"]*\\b${classHint}\\b[^"]*"[^>]*>([\\s\\S]{0,300}?)</span>`, 'i');
  const m = fragment.match(re);
  if (!m) return null;
  const text = textOf(m[1]);
  return text.length > 0 ? text : null;
}

/** First play-lh.googleusercontent.com image in the fragment (icon on listing cards). */
function extractFirstIcon(fragment: string): string | null {
  const m = fragment.match(/https:\/\/play-lh\.googleusercontent\.com\/[A-Za-z0-9_\-=_]+/);
  if (!m) return null;
  // Upgrade to a square 128px render if it's a sized variant already.
  const url = m[0];
  return url.includes('=') ? url.replace(/=(s|w)\d+[^=]*$/i, '=s128-rw') : `${url}=s128-rw`;
}

// ---------------------------------------------------------------------------
// Details-page parsing
// ---------------------------------------------------------------------------

export interface RawDetails {
  name: string | null;
  iconUrl: string | null;
  shortDescription: string | null;
  fullDescription: string | null;
  rating: number | null;
  reviewCount: number | null;
  installText: string | null;
  category: string | null;
  priceText: string | null;
  isFree: boolean;
  developer: string | null;
}

/** "100K+" / "1.4M+" / "500+" -> 100000 / 1400000 / 500. */
export function parseInstallText(text: string | null): number | null {
  if (!text) return null;
  const m = text.replace(/\s+/g, '').match(/^([\d.,]+)([KMB])?\+?$/i);
  if (!m) return null;
  const num = Number(m[1].replace(/,/g, ''));
  if (!Number.isFinite(num)) return null;
  const suffix = m[2]?.toUpperCase();
  const mult = suffix === 'K' ? 1_000 : suffix === 'M' ? 1_000_000 : suffix === 'B' ? 1_000_000_000 : 1;
  const value = Math.round(num * mult);
  return value > 0 ? value : null;
}

/** "2.62M" / "1,904" / "45K" -> number. */
export function parseCountText(text: string | null): number | null {
  if (!text) return null;
  const cleaned = text.replace(/\s+/g, '');
  const m = cleaned.match(/^([\d.,]+)([KMB])?$/i);
  if (!m) return null;
  const num = Number(m[1].replace(/,/g, ''));
  if (!Number.isFinite(num)) return null;
  const suffix = m[2]?.toUpperCase();
  const mult = suffix === 'K' ? 1_000 : suffix === 'M' ? 1_000_000 : suffix === 'B' ? 1_000_000_000 : 1;
  const value = Math.round(num * mult);
  return value > 0 ? value : null;
}

/**
 * Parse an app details page (desktop HTML) into raw fields.
 * All fields nullable; caller decides what is fatal.
 */
export function parseAppDetails(html: string): RawDetails {
  const out: RawDetails = {
    name: null,
    iconUrl: null,
    shortDescription: null,
    fullDescription: null,
    rating: null,
    reviewCount: null,
    installText: null,
    category: null,
    priceText: null,
    isFree: true,
    developer: null,
  };

  // Name: itemprop=name (falls back to og:title).
  const nameM =
    html.match(/itemprop="name"[^>]*>([\s\S]{0,200}?)</) ??
    html.match(/property="og:title" content="([^"]{0,200})"/);
  if (nameM) {
    const t = textOf(nameM[1]);
    if (t) out.name = t;
  }

  // Icon: the alt="Icon image" img (falls back to first large play-lh URL).
  const iconM = html.match(
    /<img[^>]*alt="Icon image"[^>]*src="(https:\/\/play-lh\.googleusercontent\.com\/[^"]+)"/,
  );
  if (iconM) {
    out.iconUrl = normalizeIconUrl(iconM[1]);
  } else {
    const any = html.match(/https:\/\/play-lh\.googleusercontent\.com\/[A-Za-z0-9_\-=_]+/);
    if (any) out.iconUrl = normalizeIconUrl(any[0]);
  }

  // Short description: meta description (first line of listing copy).
  const shortM = html.match(/itemprop="description" content="([^"]{0,400})"/);
  if (shortM) {
    const d = decodeHtmlEntities(shortM[1]).trim();
    if (d) out.shortDescription = d;
  }

  // Full description block: <div data-g-id="description"> ... until closing div.
  const fullIdx = html.indexOf('data-g-id="description"');
  if (fullIdx >= 0) {
    const seg = html.slice(fullIdx, fullIdx + 20_000);
    const end = seg.indexOf('</div>');
    if (end > 0) {
      const text = textOf(seg.slice(seg.indexOf('>') + 1, end));
      if (text) out.fullDescription = text.slice(0, 4000);
    }
  }

  // Rating: aria-label "Rated 4.0 stars out of five stars" (fallback: >4.0< inside starRating).
  const ratingM = html.match(/aria-label="Rated (\d(?:\.\d)?) stars? out of five stars"/);
  if (ratingM) {
    const r = Number(ratingM[1]);
    if (r >= 0 && r <= 5) out.rating = r;
  } else {
    const alt = html.match(/itemprop="starRating"[\s\S]{0,400}?<span[^>]*>(\d(?:\.\d)?)</);
    if (alt) {
      const r = Number(alt[1]);
      if (r >= 0 && r <= 5) out.rating = r;
    }
  }

  // Review count: "2.62M reviews" text in the stats row (fallback: aria "N reviews").
  const reviewsM = html.match(/<div class="g1rdde">\s*([\d.,]+\s*[KMB]?)\s*reviews?\s*</);
  if (reviewsM) {
    out.reviewCount = parseCountText(reviewsM[1]);
  } else {
    const ariaM = html.match(/aria-label="([\d.,]+[KMB]?) reviews?"/i);
    if (ariaM) out.reviewCount = parseCountText(ariaM[1]);
  }

  // Downloads: <div class="ClM7O">100+</div><div class="g1rdde">Downloads</div>
  const dlM = html.match(/<div class="ClM7O">\s*([^<]+?)\s*<\/div>\s*<div class="g1rdde">\s*Downloads/);
  if (dlM) out.installText = dlM[1].trim();

  // Category: itemprop=genre ... >Category< inside the genre chip.
  const genreIdx = html.indexOf('itemprop="genre"');
  if (genreIdx >= 0) {
    const seg = html.slice(genreIdx, genreIdx + 800);
    const catM = seg.match(/<span[^>]*>\s*([^<>]+?)\s*<\/span><a/);
    if (catM) out.category = decodeHtmlEntities(catM[1]).trim();
  }

  // Price: itemprop=price content="0" -> Free; nonzero cents otherwise.
  const priceM = html.match(/itemprop="price" content="(\d+(?:\.\d+)?)"/);
  if (priceM) {
    const cents = Number(priceM[1]);
    out.isFree = cents === 0;
    out.priceText = cents === 0 ? 'Free' : `$${(cents / 100).toFixed(2)}`;
  } else {
    out.isFree = true;
    out.priceText = null;
  }

  // Developer: the developer link in the title block.
  const devM = html.match(/<a href="\/store\/apps\/developer\?id=([^"]+)">/);
  if (devM) out.developer = decodeHtmlEntities(devM[1].replace(/\+/g, ' ')).trim();

  return out;
}

function normalizeIconUrl(url: string): string {
  const eq = url.indexOf('=');
  if (eq > 0) return url.slice(0, eq) + '=s256-rw';
  return url + '=s256-rw';
}

/**
 * PublicPlayStoreCatalogSource — discovers the full catalog by:
 *  1. fetching the developer listing page,
 *  2. validating + deduping package ids,
 *  3. fetching each app's details page for metadata (bounded concurrency),
 *  4. tolerating per-app metadata failures.
 */
export class PublicPlayStoreCatalogSource implements CatalogSource {
  readonly name = 'public_play_store';

  constructor(
    private readonly developerName: string,
    private readonly listingPath: string,
    // NOTE: fetch must be bound — calling the unbound global as a method throws
    // "Illegal invocation" on the Workers runtime.
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
    private readonly options: { concurrency?: number; detailsTimeoutMs?: number } = {},
  ) {}

  async discoverApps(): Promise<DiscoveredCatalog> {
    const fetchedAt = new Date().toISOString();
    const listingUrl = `${PLAY_BASE}${this.listingPath}`;
    const listingRes = await this.fetchImpl(listingUrl, {
      headers: { 'User-Agent': BROWSER_UA, 'Accept-Language': 'en-US,en;q=0.9' },
      redirect: 'follow',
    });
    if (!listingRes.ok) {
      throw new Error(`listing_fetch_failed status=${listingRes.status}`);
    }
    const listingHtml = await listingRes.text();
    if (!looksLikeHtmlDocument(listingHtml)) {
      throw new Error('listing_not_html');
    }
    const { entries, rejected } = parseDeveloperListing(listingHtml, this.developerName);
    if (entries.length === 0) {
      throw new Error('listing_parsed_zero_apps');
    }

    const metadataFailures: { packageName: string; error: string }[] = [];
    const apps: CatalogApp[] = [];

    const concurrency = this.options.concurrency ?? 4;
    const queue = [...entries];
    const results = new Map<string, CatalogApp>();

    const worker = async (): Promise<void> => {
      for (;;) {
        const entry = queue.shift();
        if (!entry) return;
        try {
          const app = await this.fetchAppMetadata(entry);
          results.set(entry.packageName, app);
        } catch (err) {
          metadataFailures.push({
            packageName: entry.packageName,
            error: err instanceof Error ? err.message : 'unknown',
          });
          // Fall back to listing-level data so a details-page failure never drops the app.
          results.set(entry.packageName, listingFallbackApp(entry, fetchedAt));
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, queue.length) }, worker));

    for (const entry of entries) {
      const app = results.get(entry.packageName);
      if (app) apps.push(app);
    }

    return { apps, source: this.name, fetchedAt, metadataFailures, rejectedApps: rejected };
  }

  /** Fetch + normalize one app's details page. Listing data seeds required fields. */
  async fetchAppMetadata(entry: ListingEntry): Promise<CatalogApp> {
    const now = new Date().toISOString();
    // Workers-safe timeout: AbortSignal.timeout is unbound; setTimeout with a
    // captured abort can throw "Illegal invocation" on the Workers runtime.
    let res: Response;
    try {
      res = await this.fetchImpl(`${toStoreUrl(entry.packageName)}&hl=en&gl=US`, {
        headers: { 'User-Agent': BROWSER_UA, 'Accept-Language': 'en-US,en;q=0.9' },
        redirect: 'follow',
        signal: AbortSignal.timeout(this.options.detailsTimeoutMs ?? 8_000),
      });
    } catch (err) {
      throw new Error(`details_fetch_error ${err instanceof Error ? err.name : 'unknown'}`);
    }
    if (!res.ok) throw new Error(`details_fetch_failed status=${res.status}`);
    const html = await res.text();
    if (!looksLikeHtmlDocument(html) || html.includes('We\'re sorry, the requested URL was not found')) {
      throw new Error('details_not_found');
    }
    const raw = parseAppDetails(html);
    return toCatalogApp(raw, entry, now);
  }
}

/** Build a catalog app from listing data when details fetch fails (never drop the app). */
function listingFallbackApp(entry: ListingEntry, now: string): CatalogApp {
  return {
    packageName: entry.packageName,
    name: entry.name ?? entry.packageName,
    iconUrl: entry.iconUrl,
    storeUrl: toStoreUrl(entry.packageName),
    shortDescription: null,
    fullDescription: null,
    rating: null,
    reviewCount: null,
    installText: null,
    estimatedMinimumInstalls: null,
    category: null,
    priceText: null,
    isFree: true,
    developer: entry.developer,
    lastMetadataRefresh: null,
    firstDiscoveredAt: now,
    lastSeenAt: now,
    enabledForPromotion: true,
    promotionMultiplier: 1,
  };
}

/** Merge raw details with listing entry into the normalized model. */
export function toCatalogApp(raw: RawDetails, entry: ListingEntry, now: string): CatalogApp {
  return {
    packageName: entry.packageName,
    name: raw.name ?? entry.name ?? entry.packageName,
    iconUrl: raw.iconUrl ?? entry.iconUrl,
    storeUrl: toStoreUrl(entry.packageName),
    shortDescription: raw.shortDescription,
    fullDescription: raw.fullDescription,
    rating: raw.rating,
    reviewCount: raw.reviewCount,
    installText: raw.installText,
    estimatedMinimumInstalls: parseInstallText(raw.installText),
    category: raw.category,
    priceText: raw.priceText,
    isFree: raw.isFree,
    developer: raw.developer ?? entry.developer,
    lastMetadataRefresh: now,
    firstDiscoveredAt: now,
    lastSeenAt: now,
    enabledForPromotion: true,
    promotionMultiplier: 1,
  };
}
