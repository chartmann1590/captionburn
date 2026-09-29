import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  extractPackageFromUrl,
  isValidPackageId,
  parseAppDetails,
  parseDeveloperListing,
  parseInstallText,
  parseCountText,
  PublicPlayStoreCatalogSource,
  type ListingEntry,
} from '../src/playStoreProvider';

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const devPage = readFileSync(join(fixturesDir, 'developer-page-hartmann-studios.html'), 'utf8');
const captionBurnPage = readFileSync(join(fixturesDir, 'details-captionburn.html'), 'utf8');
const weatherPage = readFileSync(join(fixturesDir, 'details-auracast-weather.html'), 'utf8');
const qrcodePage = readFileSync(join(fixturesDir, 'details-charles-qrcode.html'), 'utf8');
const ratedPage = readFileSync(join(fixturesDir, 'details-threads-with-rating.html'), 'utf8');

describe('isValidPackageId', () => {
  it('accepts normal package ids', () => {
    expect(isValidPackageId('com.charlesh.captionburn')).toBe(true);
    expect(isValidPackageId('com.auracast.weather')).toBe(true);
  });

  it('rejects unrelated and malformed links', () => {
    expect(isValidPackageId('')).toBe(false);
    expect(isValidPackageId('details')).toBe(false);
    expect(isValidPackageId('com.')).toBe(false);
    expect(isValidPackageId('com..app')).toBe(false);
    expect(isValidPackageId('com.app<script>')).toBe(false);
    expect(isValidPackageId('a'.repeat(101))).toBe(false);
    expect(isValidPackageId(null)).toBe(false);
    expect(isValidPackageId(42)).toBe(false);
  });
});

describe('extractPackageFromUrl', () => {
  it('extracts ids from relative and absolute details URLs', () => {
    expect(extractPackageFromUrl('/store/apps/details?id=com.charlesh.captionburn')).toBe('com.charlesh.captionburn');
    expect(extractPackageFromUrl('https://play.google.com/store/apps/details?id=com.charles.qrcode&hl=en')).toBe(
      'com.charles.qrcode',
    );
  });

  it('rejects non-play hosts and non-details links', () => {
    expect(extractPackageFromUrl('https://evil.example.com/store/apps/details?id=com.x.y')).toBeNull();
    expect(extractPackageFromUrl('/store/apps/developer?id=Hartmann+Studios')).toBeNull();
    expect(extractPackageFromUrl('')).toBeNull();
  });

  it('normalizes duplicate links to the same package', () => {
    const a = extractPackageFromUrl('/store/apps/details?id=com.charlesh.captionburn&hl=en');
    const b = extractPackageFromUrl('https://play.google.com/store/apps/details?id=com.charlesh.captionburn');
    expect(a).toBe(b);
  });
});

describe('parseDeveloperListing (real fixture)', () => {
  const parsed = parseDeveloperListing(devPage, 'Hartmann Studios');

  it('finds the real Hartmann Studios apps on the developer page', () => {
    const pkgs = parsed.entries.map((e) => e.packageName).sort();
    expect(pkgs).toContain('com.charlesh.captionburn');
    expect(pkgs).toContain('com.auracast.weather');
    expect(pkgs).toContain('com.charles.qrcode');
    expect(pkgs).toContain('com.cruisewatch.app');
    expect(pkgs.length).toBeGreaterThanOrEqual(20);
  });

  it('extracts names and icons from listing cards', () => {
    const caption = parsed.entries.find((e) => e.packageName === 'com.charlesh.captionburn');
    expect(caption?.name).toBe('CaptionBurn: Video Captions');
    expect(caption?.iconUrl).toContain('play-lh.googleusercontent.com');
    const owe = parsed.entries.find((e) => e.packageName === 'com.charles.owefolk');
    expect(owe?.name).toBe('Owefolk');
  });

  it('rejects entries with mismatched developer names', () => {
    const foreign = parseDeveloperListing(devPage, 'Some Other Developer');
    expect(foreign.entries.length).toBe(0);
    expect(foreign.rejected.length).toBeGreaterThan(0);
  });

  it('returns no duplicate packages', () => {
    const pkgs = parsed.entries.map((e) => e.packageName);
    expect(new Set(pkgs).size).toBe(pkgs.length);
  });
});

describe('parseAppDetails (real fixture, unrated app)', () => {
  const raw = parseAppDetails(captionBurnPage);

  it('extracts the real listing data for CaptionBurn', () => {
    expect(raw.name).toBe('CaptionBurn: Video Captions');
    expect(raw.iconUrl).toContain('play-lh.googleusercontent.com');
    expect(raw.shortDescription).toBeTruthy();
    expect(raw.fullDescription).toBeTruthy();
    expect(raw.installText).toBe('100+');
    expect(parseInstallText(raw.installText)).toBe(100);
    expect(raw.category).toBe('Video Players & Editors');
    expect(raw.developer).toBe('Hartmann Studios');
    expect(raw.isFree).toBe(true);
  });

  it('returns null rating/review count when Play shows none (too few ratings)', () => {
    expect(raw.rating).toBeNull();
    expect(raw.reviewCount).toBeNull();
  });
});

describe('parseAppDetails (rated app fixture)', () => {
  const raw = parseAppDetails(ratedPage);

  it('extracts rating and review count when present', () => {
    expect(raw.rating).toBeCloseTo(4.0, 5);
    expect(raw.reviewCount).toBeGreaterThan(1_000_000);
  });
});

describe('parseAppDetails (small apps)', () => {
  it('parses the weather app details', () => {
    const raw = parseAppDetails(weatherPage);
    expect(raw.name).toBe('AuraCast Weather');
    expect(raw.installText).toBe('10+');
    expect(parseInstallText(raw.installText)).toBe(10);
  });

  it('parses the qr code app details', () => {
    const raw = parseAppDetails(qrcodePage);
    expect(raw.name).toBe('QR Scanner: Code Reader');
    expect(raw.installText).toBe('10+');
  });
});

describe('parseInstallText / parseCountText', () => {
  it('converts Play install labels to minimum installs', () => {
    expect(parseInstallText('100+')).toBe(100);
    expect(parseInstallText('100K+')).toBe(100_000);
    expect(parseInstallText('1.4M+')).toBe(1_400_000);
    expect(parseInstallText('2B+')).toBe(2_000_000_000);
    expect(parseInstallText('garbage')).toBeNull();
    expect(parseInstallText(null)).toBeNull();
  });

  it('converts review count labels', () => {
    expect(parseCountText('2.62M')).toBe(2_620_000);
    expect(parseCountText('1,904')).toBe(1904);
    expect(parseCountText('45K')).toBe(45_000);
    expect(parseCountText('k')).toBeNull();
  });
});

describe('PublicPlayStoreCatalogSource', () => {
  function listingEntry(pkg: string): ListingEntry {
    return { packageName: pkg, name: pkg, iconUrl: null, developer: 'Hartmann Studios' };
  }

  it('normalizes a fetched details page into the catalog model', async () => {
    const fetchImpl = (async () =>
      new Response(captionBurnPage, { status: 200, headers: { 'content-type': 'text/html' } })) as typeof fetch;
    const source = new PublicPlayStoreCatalogSource('Hartmann Studios', '/store/apps/developer?id=Hartmann+Studios', fetchImpl);
    const app = await source.fetchAppMetadata(listingEntry('com.charlesh.captionburn'));
    expect(app.packageName).toBe('com.charlesh.captionburn');
    expect(app.name).toBe('CaptionBurn: Video Captions');
    expect(app.storeUrl).toBe('https://play.google.com/store/apps/details?id=com.charlesh.captionburn');
    expect(app.enabledForPromotion).toBe(true);
    expect(app.promotionMultiplier).toBe(1);
  });

  it('reports metadata failures and still returns listing data when details fetch fails', async () => {
    // Listing entry fixtures: what a real listing pass would have provided before
    // details enrichment.
    const listingHtml = devPage
      .replace(/<span class="DdYX5">[^<]*<\/span>/g, '')
      .replace(/<span class="wMUdtb">[^<]*<\/span>/g, '');
    const fetchImpl = (async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('developer?')) {
        return new Response(listingHtml, { status: 200 });
      }
      return new Response('not found', { status: 404 });
    }) as unknown as typeof fetch;

    const source = new PublicPlayStoreCatalogSource('Hartmann Studios', '/store/apps/developer?id=Hartmann+Studios', fetchImpl, { concurrency: 8 });
    const discovered = await source.discoverApps();
    // Every app survives via listing fallback even though all details fetches 404'd.
    expect(discovered.apps.length).toBeGreaterThanOrEqual(15);
    expect(discovered.metadataFailures.length).toBe(discovered.apps.length);
    const surviving = discovered.apps.find((a) => a.packageName === 'com.charlesh.captionburn');
    expect(surviving).toBeDefined();
    expect(surviving?.name).toBe('com.charlesh.captionburn'); // fallback: package id as name
  });

  it('throws a descriptive error when the listing page cannot be fetched', async () => {
    const fetchImpl = (async () => new Response('nope', { status: 503 })) as unknown as typeof fetch;
    const source = new PublicPlayStoreCatalogSource('Hartmann Studios', '/store/apps/developer?id=Hartmann+Studios', fetchImpl);
    await expect(source.discoverApps()).rejects.toThrow('listing_fetch_failed');
  });

  it('throws when the listing HTML parses to zero apps (HTML change protection)', async () => {
    const fetchImpl = (async () =>
      new Response('<html><head><title>changed</title></head><body></body></html>'.repeat(100), { status: 200 })) as unknown as typeof fetch;
    const source = new PublicPlayStoreCatalogSource('Hartmann Studios', '/store/apps/developer?id=Hartmann+Studios', fetchImpl);
    await expect(source.discoverApps()).rejects.toThrow('listing_parsed_zero_apps');
  });
});
