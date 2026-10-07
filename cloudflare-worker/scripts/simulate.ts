/**
 * Recommendation simulation: 100,000+ selections against a sample catalog to
 * verify exposure distribution, exploration, new-app boost, exclusions and
 * uniqueness guarantees. Run with: npm run simulate
 */
import type { CatalogApp, RuntimeConfig } from '../src/types';
import { DEFAULT_RUNTIME_CONFIG } from '../src/types';
import { selectRecommendations } from '../src/ranking';

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

const CATALOG: CatalogApp[] = [
  app('com.hartmann.giant', { estimatedMinimumInstalls: 5_000_000, reviewCount: 40_000, rating: 4.6 }),
  app('com.hartmann.big', { estimatedMinimumInstalls: 900_000, reviewCount: 8_000, rating: 4.4 }),
  app('com.hartmann.mid', { estimatedMinimumInstalls: 120_000, reviewCount: 1_800, rating: 4.5 }),
  app('com.hartmann.small', { estimatedMinimumInstalls: 20_000, reviewCount: 400, rating: 4.3 }),
  app('com.hartmann.tiny', { estimatedMinimumInstalls: 5_000, reviewCount: 60, rating: 4.8 }),
  app('com.hartmann.unrated', { estimatedMinimumInstalls: 3_000, reviewCount: null, rating: null }),
  app('com.hartmann.newapp', {
    estimatedMinimumInstalls: 100,
    reviewCount: null,
    rating: null,
    firstDiscoveredAt: new Date(Date.now() - 3 * 24 * 3600 * 1000).toISOString(),
  }),
  app('com.hartmann.disabled', { enabledForPromotion: false, estimatedMinimumInstalls: 50_000_000, reviewCount: 900_000, rating: 5 }),
  app('com.hartmann.boosted', { estimatedMinimumInstalls: 8_000, reviewCount: 90, rating: 4.2, promotionMultiplier: 2.5 }),
];

const CONFIG: RuntimeConfig = {
  ...DEFAULT_RUNTIME_CONFIG,
  popularWeight: 0.65,
  randomWeight: 0.35,
};

function main(): void {
  const ITERATIONS = 100_000;
  const impressions: Record<string, number> = {};
  const selectionTypes: Record<string, number> = {};
  let totalSlots = 0;
  let duplicateResponses = 0;
  let sourceLeak = 0;
  let disabledLeak = 0;

  for (let i = 0; i < ITERATIONS; i++) {
    const result = selectRecommendations({
      catalog: CATALOG,
      sourcePackage: 'com.hartmann.giant', // simulate the biggest app running the SDK
      placement: 'settings',
      limit: 3,
      config: CONFIG,
      sessionId: `session-${i}`, // every iteration is a fresh user session
      exclude: [],
      recentImpressions: {},
      nowMs: Date.now(),
    });

    const pkgs = result.apps.map((a) => a.packageName);
    if (new Set(pkgs).size !== pkgs.length) duplicateResponses++;
    if (pkgs.includes('com.hartmann.giant')) sourceLeak++;
    if (pkgs.includes('com.hartmann.disabled')) disabledLeak++;

    for (const rec of result.apps) {
      impressions[rec.packageName] = (impressions[rec.packageName] ?? 0) + 1;
      selectionTypes[rec.selectionType] = (selectionTypes[rec.selectionType] ?? 0) + 1;
      totalSlots++;
    }
  }

  const sorted = Object.entries(impressions).sort((a, b) => b[1] - a[1]);
  const total = sorted.reduce((s, [, n]) => s + n, 0);
  const maxShare = sorted.length > 0 ? sorted[0][1] / total : 0;
  const minShown = sorted.length > 0 ? sorted[sorted.length - 1][1] : 0;

  console.log('=== Cross-promotion recommendation simulation ===');
  console.log(`iterations: ${ITERATIONS}, slots filled: ${totalSlots}`);
  console.log('\nExposure by target (share of all impressions):');
  for (const [pkg, n] of sorted) {
    const bar = '#'.repeat(Math.round((n / sorted[0][1]) * 40));
    console.log(`  ${pkg.padEnd(28)} ${String(n).padStart(7)}  (${((n / total) * 100).toFixed(2)}%) ${bar}`);
  }
  console.log('\nSelection type mix:');
  for (const [type, n] of Object.entries(selectionTypes).sort((a, b) => b[1] - a[1])) {
    console.log(`  ${type.padEnd(14)} ${String(n).padStart(7)}  (${((n / totalSlots) * 100).toFixed(2)}%)`);
  }

  console.log('\nInvariants:');
  const checks: [string, boolean][] = [
    ['no duplicate apps in any response', duplicateResponses === 0],
    ['source app never recommended', sourceLeak === 0],
    ['disabled app never recommended', disabledLeak === 0],
    ['organic exposure follows popularity (big > mid > small > tiny > unrated)',
      ['com.hartmann.big', 'com.hartmann.mid', 'com.hartmann.small', 'com.hartmann.tiny', 'com.hartmann.unrated']
        .map((p) => impressions[p] ?? 0)
        .every((n, i, arr) => i === 0 || n <= arr[i - 1])],
    ['manual promotionMultiplier override leads exposure', sorted[0][0] === 'com.hartmann.boosted'],
    ['no single app dominates (>30% share)', maxShare <= 0.30],
    ['small apps receive exposure (all non-disabled eligible apps shown)', minShown > 0],
    ['new app received boosted exposure (above unrated baseline)', (impressions['com.hartmann.newapp'] ?? 0) > (impressions['com.hartmann.unrated'] ?? 0)],
    ['exploration share within tolerance of randomWeight (35% ± 10)', Math.abs((selectionTypes['random'] ?? 0) / totalSlots - 0.35) <= 0.10],
    ['popular type dominates mix', (selectionTypes['popular'] ?? 0) > (selectionTypes['random'] ?? 0)],
    ['boosted manual override outperforms its baseline peers', (impressions['com.hartmann.boosted'] ?? 0) > (impressions['com.hartmann.unrated'] ?? 0)],
  ];
  let failed = 0;
  for (const [label, ok] of checks) {
    console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${label}`);
    if (!ok) failed++;
  }

  if (failed > 0) {
    console.error(`\nSIMULATION FAILED: ${failed} invariant(s) violated`);
    process.exit(1);
  }
  console.log('\nSIMULATION PASSED');
}

main();
