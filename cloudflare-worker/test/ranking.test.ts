import { describe, expect, it } from 'vitest';
import type { CatalogApp, RuntimeConfig } from '../src/types';
import { DEFAULT_RUNTIME_CONFIG } from '../src/types';
import { hash32, selectRecommendations, unitInterval, popularityScore } from '../src/ranking';

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
    firstDiscoveredAt: '2020-01-01T00:00:00Z',
    lastSeenAt: null,
    enabledForPromotion: true,
    promotionMultiplier: 1,
    ...overrides,
  };
}

const CONFIG: RuntimeConfig = { ...DEFAULT_RUNTIME_CONFIG };

describe('hash32 / unitInterval', () => {
  it('is deterministic and well distributed', () => {
    expect(hash32('abc')).toBe(hash32('abc'));
    expect(hash32('abc')).not.toBe(hash32('abd'));
    for (let i = 0; i < 100; i++) {
      const v = unitInterval(`seed-${i}`);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });
});

describe('popularityScore', () => {
  it('ranks high-install apps above low-install apps', () => {
    const now = Date.now();
    const big = popularityScore(app('com.big', { estimatedMinimumInstalls: 5_000_000, reviewCount: 40_000, rating: 4.6 }), now);
    const small = popularityScore(app('com.small', { estimatedMinimumInstalls: 3_000, reviewCount: 5, rating: 4.0 }), now);
    expect(big.score).toBeGreaterThan(small.score);
  });

  it('never invents metrics: falls back to reviews when installs missing', () => {
    const now = Date.now();
    const reviewed = popularityScore(app('com.reviewed', { reviewCount: 20_000, rating: 4.5 }), now);
    expect(reviewed.score).toBeGreaterThan(0);
    expect(reviewed.factors.installsScore).toBe(0);
  });

  it('gives unrated apps a neutral (not negative) score', () => {
    const now = Date.now();
    const unrated = popularityScore(app('com.unrated', { rating: null, reviewCount: null }), now);
    expect(unrated.score).toBeGreaterThan(0);
  });
});

describe('selectRecommendations', () => {
  const catalog = [
    app('com.source', { estimatedMinimumInstalls: 1_000_000 }),
    app('com.popular', { estimatedMinimumInstalls: 5_000_000, reviewCount: 40_000, rating: 4.6 }),
    app('com.mid', { estimatedMinimumInstalls: 100_000, reviewCount: 2_000, rating: 4.4 }),
    app('com.small', { estimatedMinimumInstalls: 10_000, reviewCount: 200, rating: 4.2 }),
    app('com.disabled', { enabledForPromotion: false, estimatedMinimumInstalls: 50_000_000 }),
  ];

  it('excludes the source app, disabled apps and explicit excludes', () => {
    const result = selectRecommendations({
      catalog,
      sourcePackage: 'com.source',
      placement: 'settings',
      limit: 6,
      config: CONFIG,
      sessionId: 's1',
      exclude: ['com.small'],
      recentImpressions: {},
      nowMs: Date.now(),
    });
    const pkgs = result.apps.map((a) => a.packageName);
    expect(pkgs).not.toContain('com.source');
    expect(pkgs).not.toContain('com.disabled');
    expect(pkgs).not.toContain('com.small');
  });

  it('never returns duplicates', () => {
    const result = selectRecommendations({
      catalog: [...catalog, app('com.extra1'), app('com.extra2'), app('com.extra3')],
      sourcePackage: 'com.source',
      placement: 'settings',
      limit: 5,
      config: CONFIG,
      sessionId: 's2',
      exclude: [],
      recentImpressions: {},
      nowMs: Date.now(),
    });
    const pkgs = result.apps.map((a) => a.packageName);
    expect(new Set(pkgs).size).toBe(pkgs.length);
    expect(pkgs.length).toBe(5);
  });

  it('caps results at available eligible apps', () => {
    const result = selectRecommendations({
      catalog: [app('com.source'), app('com.only1')],
      sourcePackage: 'com.source',
      placement: 'settings',
      limit: 3,
      config: CONFIG,
      sessionId: 's3',
      exclude: [],
      recentImpressions: {},
      nowMs: Date.now(),
    });
    expect(result.apps.length).toBe(1);
  });

  it('gives the popular app more exposure than a small app over many runs', () => {
    const impressions: Record<string, number> = {};
    for (let i = 0; i < 3_000; i++) {
      const result = selectRecommendations({
        catalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      for (const rec of result.apps) {
        impressions[rec.packageName] = (impressions[rec.packageName] ?? 0) + 1;
      }
    }
    expect(impressions['com.popular']).toBeGreaterThan(impressions['com.small']);
  });

  it('still explores: small apps are shown sometimes', () => {
    const impressions: Record<string, number> = {};
    for (let i = 0; i < 3_000; i++) {
      const result = selectRecommendations({
        catalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      for (const rec of result.apps) {
        impressions[rec.packageName] = (impressions[rec.packageName] ?? 0) + 1;
      }
    }
    expect(impressions['com.small']).toBeGreaterThan(0);
  });

  it('boosts newly discovered apps', () => {
    const newApp = app('com.brandnew', { firstDiscoveredAt: new Date(Date.now() - 2 * 24 * 3600 * 1000).toISOString() });
    const withNew = [...catalog, newApp];
    const impressions: Record<string, number> = {};
    for (let i = 0; i < 3_000; i++) {
      const result = selectRecommendations({
        catalog: withNew,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      for (const rec of result.apps) {
        impressions[rec.packageName] = (impressions[rec.packageName] ?? 0) + 1;
      }
    }
    const newShare = impressions['com.brandnew'] ?? 0;
    const midShare = impressions['com.mid'] ?? 0;
    // The new app (no installs, no ratings) must outperform an established mid app.
    expect(newShare).toBeGreaterThan(midShare * 0.5);
  });

  it('stops boosting new apps after the boost window', () => {
    const oldApp = app('com.oldnew', { firstDiscoveredAt: new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString() });
    const withOld = [...catalog, oldApp];
    let newAppSelections = 0;
    for (let i = 0; i < 2_000; i++) {
      const result = selectRecommendations({
        catalog: withOld,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 3,
        config: CONFIG,
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      if (result.selectionTypes.includes('new_app_boost')) newAppSelections++;
    }
    expect(newAppSelections).toBe(0);
  });

  it('is session-stable: same session and placement get the same set within the TTL', () => {
    const now = Date.now();
    const a = selectRecommendations({
      catalog,
      sourcePackage: 'com.source',
      placement: 'settings',
      limit: 3,
      config: CONFIG,
      sessionId: 'stable-session',
      exclude: [],
      recentImpressions: {},
      nowMs: now,
    });
    const b = selectRecommendations({
      catalog,
      sourcePackage: 'com.source',
      placement: 'settings',
      limit: 3,
      config: CONFIG,
      sessionId: 'stable-session',
      exclude: [],
      recentImpressions: {},
      nowMs: now,
    });
    expect(a.apps.map((x) => x.packageName)).toEqual(b.apps.map((x) => x.packageName));
  });

  it('rotates across sessions: different sessions see different sets', () => {
    // Realistic catalog: source + 12 promotable apps of varying popularity.
    const bigCatalog = [
      app('com.source'),
      ...Array.from({ length: 12 }, (_, i) =>
        app(`com.app.${i}`, {
          estimatedMinimumInstalls: 1_000_000 / (i + 1),
          reviewCount: 10_000 / (i + 1),
          rating: 4.5,
        }),
      ),
    ];
    const sets = new Set<string>();
    const runs = 300;
    for (let i = 0; i < runs; i++) {
      const result = selectRecommendations({
        catalog: bigCatalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 3,
        config: CONFIG,
        sessionId: `session-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      sets.add(result.apps.map((x) => x.packageName).join(','));
    }
    // With weighted selection, popular lineups recur, but a meaningful share of
    // sessions must see a different trio.
    expect(sets.size).toBeGreaterThan(runs * 0.15);
  });

  it('penalizes recently-impressed apps (recency rotation)', () => {
    let penalized = 0;
    let neutral = 0;
    for (let i = 0; i < 1_000; i++) {
      const withPenalty = selectRecommendations({
        catalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `p-${i}`,
        exclude: [],
        recentImpressions: { 'com.popular': 10 },
        nowMs: Date.now(),
      });
      const withoutPenalty = selectRecommendations({
        catalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `q-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      if (withPenalty.apps[0]?.packageName === 'com.popular') penalized++;
      if (withoutPenalty.apps[0]?.packageName === 'com.popular') neutral++;
    }
    expect(penalized).toBeLessThan(neutral);
  });

  it('supports the promotionMultiplier override', () => {
    const boosted = [...catalog.slice(0, 4), app('com.boosted', { estimatedMinimumInstalls: 500, promotionMultiplier: 25 })];
    let boostedWins = 0;
    for (let i = 0; i < 2_000; i++) {
      const result = selectRecommendations({
        catalog: boosted,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: CONFIG,
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      if (result.apps[0]?.packageName === 'com.boosted') boostedWins++;
    }
    // A 25x multiplier on a tiny app should win the popular slot most of the time.
    expect(boostedWins).toBeGreaterThan(1_000);
  });

  it('respects remote config popularWeight = 0 (pure exploration)', () => {
    let popularSelections = 0;
    for (let i = 0; i < 1_000; i++) {
      const result = selectRecommendations({
        catalog,
        sourcePackage: 'com.source',
        placement: 'settings',
        limit: 1,
        config: { ...CONFIG, popularWeight: 0, randomWeight: 1 },
        sessionId: `run-${i}`,
        exclude: [],
        recentImpressions: {},
        nowMs: Date.now(),
      });
      if (result.selectionTypes[0] === 'popular') popularSelections++;
    }
    expect(popularSelections).toBe(0);
  });

  it('returns empty when everything is excluded', () => {
    const result = selectRecommendations({
      catalog: [app('com.only')],
      sourcePackage: 'com.only',
      placement: 'settings',
      limit: 3,
      config: CONFIG,
      sessionId: 's',
      exclude: [],
      recentImpressions: {},
      nowMs: Date.now(),
    });
    expect(result.apps).toEqual([]);
  });
});
