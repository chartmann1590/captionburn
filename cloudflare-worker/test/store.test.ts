import { describe, expect, it } from 'vitest';
import type { CatalogApp, DiscoveredCatalog } from '../src/types';
import { KvCatalogStore, retainPreviousMetadata, validateCatalogRefresh } from '../src/store';

function app(pkg: string, overrides: Partial<CatalogApp> = {}): CatalogApp {
  return {
    packageName: pkg,
    name: pkg,
    iconUrl: null,
    storeUrl: `https://play.google.com/store/apps/details?id=${pkg}`,
    shortDescription: null,
    fullDescription: null,
    rating: null,
    reviewCount: null,
    installText: null,
    estimatedMinimumInstalls: null,
    category: null,
    priceText: null,
    isFree: true,
    developer: 'Hartmann Studios',
    lastMetadataRefresh: null,
    firstDiscoveredAt: '2026-01-01T00:00:00Z',
    lastSeenAt: null,
    enabledForPromotion: true,
    promotionMultiplier: 1,
    ...overrides,
  };
}

function discovered(apps: CatalogApp[]): DiscoveredCatalog {
  return {
    apps,
    source: 'public_play_store',
    fetchedAt: '2026-09-29T12:00:00Z',
    metadataFailures: [],
    rejectedApps: [],
  };
}

/** Minimal KVNamespace stub backed by a Map. */
function kvStub(): KVNamespace {
  const map = new Map<string, unknown>();
  return {
    get: async (key: string, type?: unknown) => {
      const v = map.get(key);
      if (v === undefined) return null;
      return type === 'json' ? JSON.parse(JSON.stringify(v)) : String(v);
    },
    put: async (key: string, value: unknown) => {
      map.set(key, typeof value === 'string' ? JSON.parse(value) : value);
    },
  } as unknown as KVNamespace;
}

describe('validateCatalogRefresh', () => {
  it('accepts a normal refresh with no previous catalog', () => {
    const v = validateCatalogRefresh(null, [app('com.a.b'), app('com.c.d')]);
    expect(v.accepted).toBe(true);
    expect(v.newApps).toEqual(['com.a.b', 'com.c.d']);
  });

  it('rejects zero-app discoveries (HTML wipe protection)', () => {
    const v = validateCatalogRefresh([app('com.a.b'), app('com.c.d')], []);
    expect(v.accepted).toBe(false);
    expect(v.reason).toBe('zero_apps_discovered');
  });

  it('rejects dramatic unexplained drops', () => {
    const previous = Array.from({ length: 20 }, (_, i) => app(`com.x.${i}`));
    const next = [app('com.a.b')];
    const v = validateCatalogRefresh(previous, next);
    expect(v.accepted).toBe(false);
    expect(v.reason).toContain('suspicious_catalog_drop');
  });

  it('accepts a healthy refresh and reports removed apps', () => {
    const previous = [app('com.a.b'), app('com.c.d'), app('com.e.f')];
    const next = [app('com.a.b'), app('com.c.d'), app('com.e.f'), app('com.g.h')];
    const v = validateCatalogRefresh(previous, next);
    expect(v.accepted).toBe(true);
    expect(v.newApps).toEqual(['com.g.h']);
  });

  it('accepts when exactly half the catalog remains (threshold boundary)', () => {
    const previous = Array.from({ length: 10 }, (_, i) => app(`com.x.${i}`));
    const next = Array.from({ length: 5 }, (_, i) => app(`com.x.${i}`));
    expect(validateCatalogRefresh(previous, next).accepted).toBe(true);
    const next4 = Array.from({ length: 4 }, (_, i) => app(`com.x.${i}`));
    expect(validateCatalogRefresh(previous, next4).accepted).toBe(false);
  });
});

describe('retainPreviousMetadata', () => {
  it('keeps previously scraped rich metadata when details enrichment failed', () => {
    const previous = app('com.a.b', {
      name: 'Old Name',
      shortDescription: 'Great app',
      rating: 4.5,
      reviewCount: 1234,
      installText: '100K+',
      estimatedMinimumInstalls: 100000,
      category: 'Tools',
      lastMetadataRefresh: '2026-09-01T00:00:00Z',
    });
    const listingOnly = app('com.a.b', {
      name: 'New Name From Listing',
      lastSeenAt: '2026-09-29T00:00:00Z',
      lastMetadataRefresh: null,
    });
    const merged = retainPreviousMetadata(listingOnly, previous);
    // Rich metadata retained from the last successful scrape:
    expect(merged.rating).toBe(4.5);
    expect(merged.reviewCount).toBe(1234);
    expect(merged.installText).toBe('100K+');
    expect(merged.estimatedMinimumInstalls).toBe(100000);
    expect(merged.category).toBe('Tools');
    expect(merged.shortDescription).toBe('Great app');
    expect(merged.lastMetadataRefresh).toBe('2026-09-01T00:00:00Z');
    // Listing-derived control fields still update:
    expect(merged.name).toBe('New Name From Listing');
    expect(merged.lastSeenAt).toBe('2026-09-29T00:00:00Z');
  });

  it('retention is applied only to failed apps — successful scrapes overwrite', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    await store.commitDiscoveredCatalog(
      discovered([
        app('com.ok.app', { rating: 4.1, reviewCount: 10 }),
        app('com.bad.app', { rating: 3.0, reviewCount: 20 }),
      ]),
    );
    const next: DiscoveredCatalog = {
      ...discovered([
        app('com.ok.app', { rating: 4.9, reviewCount: 11, lastMetadataRefresh: '2026-09-29T12:00:00Z' }),
        app('com.bad.app', { name: 'Bad App (listing only)' }),
      ]),
      metadataFailures: [{ packageName: 'com.bad.app', error: 'details_fetch_failed status=503' }],
    };
    const result = await store.commitDiscoveredCatalog(next);
    expect(result.accepted).toBe(true);
    const catalog = (await store.getCurrentCatalog()) ?? [];
    const byPkg = new Map(catalog.map((a) => [a.packageName, a]));
    // Successful scrape overwrites everything:
    expect(byPkg.get('com.ok.app')?.rating).toBe(4.9);
    expect(byPkg.get('com.ok.app')?.reviewCount).toBe(11);
    // Failed scrape retains the last known rich metadata:
    expect(byPkg.get('com.bad.app')?.rating).toBe(3.0);
    expect(byPkg.get('com.bad.app')?.reviewCount).toBe(20);
    expect(byPkg.get('com.bad.app')?.name).toBe('Bad App (listing only)');
  });

  it('commitDiscoveredCatalog retains prior metadata for apps listed in metadataFailures', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    await store.commitDiscoveredCatalog(
      discovered([app('com.a.b', { rating: 4.2, reviewCount: 999, installText: '50K+' })]),
    );
    const failed: DiscoveredCatalog = {
      ...discovered([app('com.a.b', { name: 'Listed Name' })]),
      metadataFailures: [{ packageName: 'com.a.b', error: 'details_fetch_failed status=503' }],
    };
    const result = await store.commitDiscoveredCatalog(failed);
    expect(result.accepted).toBe(true);
    const catalog = (await store.getCurrentCatalog()) ?? [];
    expect(catalog[0].rating).toBe(4.2);
    expect(catalog[0].reviewCount).toBe(999);
    expect(catalog[0].installText).toBe('50K+');
    expect(catalog[0].name).toBe('Listed Name');
  });
});

describe('KvCatalogStore.commitDiscoveredCatalog', () => {
  it('persists catalog to current and lastKnownGood and records refresh meta', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    const result = await store.commitDiscoveredCatalog(discovered([app('com.a.b'), app('com.c.d')]));
    expect(result.accepted).toBe(true);
    expect(result.appCount).toBe(2);
    const current = await store.getCurrentCatalog();
    const lastGood = await store.getLastKnownGoodCatalog();
    expect(current?.length).toBe(2);
    expect(lastGood?.length).toBe(2);
    const meta = await store.getRefreshMeta();
    expect(meta.lastSuccessfulRefresh).not.toBeNull();
    expect(meta.lastSuccessfulAppCount).toBe(2);
  });

  it('preserves firstDiscoveredAt, enabled and multiplier across refreshes', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    await store.commitDiscoveredCatalog(
      discovered([
        app('com.a.b', { firstDiscoveredAt: '2025-06-01T00:00:00Z', enabledForPromotion: false, promotionMultiplier: 3 }),
      ]),
    );
    // Next discovery loses all fields (fresh scrape) — store must carry them forward.
    const result = await store.commitDiscoveredCatalog(discovered([app('com.a.b')]));
    expect(result.accepted).toBe(true);
    const catalog = (await store.getCurrentCatalog()) ?? [];
    expect(catalog[0].firstDiscoveredAt).toBe('2025-06-01T00:00:00Z');
    expect(catalog[0].enabledForPromotion).toBe(false);
    expect(catalog[0].promotionMultiplier).toBe(3);
  });

  it('never overwrites a valid catalog with a broken refresh', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    await store.commitDiscoveredCatalog(discovered([app('com.a.b'), app('com.c.d')]));
    const broken = await store.commitDiscoveredCatalog(discovered([]));
    expect(broken.accepted).toBe(false);
    expect((await store.getCurrentCatalog())?.length).toBe(2);
    expect((await store.getLastKnownGoodCatalog())?.length).toBe(2);
    const meta = await store.getRefreshMeta();
    expect(meta.lastRejectedReason).toBe('zero_apps_discovered');
    expect(meta.lastRefreshAttempt).not.toBeNull();
  });

  it('merges config defaults with overrides', async () => {
    const kv = kvStub();
    const store = new KvCatalogStore(kv);
    const config = await store.getConfig();
    expect(config.popularWeight).toBeCloseTo(0.65, 5);
    await store.setConfig({ ...config, popularWeight: 0.5, enabled: false });
    const updated = await store.getConfig();
    expect(updated.popularWeight).toBeCloseTo(0.5, 5);
    expect(updated.enabled).toBe(false);
    expect(updated.maxLimit).toBe(6);
  });
});
