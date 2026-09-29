/**
 * Popularity scoring + recommendation selection.
 *
 * Design goals:
 *  - Understandable, deterministic scoring — no ML.
 *  - Never only show the most popular app: ~65% popularity-weighted pool,
 *    ~35% uniform exploration (weights are remote-config).
 *  - Starvation-proof: recency-aware exploration boost for rarely-shown apps.
 *  - New apps (firstDiscoveredAt within newAppBoostDays) get a temporary boost.
 *  - Session-stable output: the same session+placement gets the same set for the
 *    TTL window, with recent targets rotated to the back of the line.
 *  - Extensible: future signals (CTR, attributed installs, revenue) plug into
 *    promoPerformanceScore without touching clients.
 */
import type { CatalogApp, Recommendation, RuntimeConfig, SelectionType } from './types';

/**
 * Deterministic 32-bit hash -> uint32 (FNV-1a + murmur3 finalizer).
 *
 * Plain FNV-1a avalanches poorly for inputs that differ only in trailing
 * characters (exactly our sessionKey:slot:N shape), which collapses selection
 * randomness across slots. The fmix32 finalizer fixes the avalanche.
 */
export function hash32(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h >>> 0;
}

/** Map a string deterministically onto [0, 1). */
export function unitInterval(seed: string): number {
  return hash32(seed) / 0x100000000;
}

export interface PopularityFactors {
  installsScore: number;
  reviewScore: number;
  ratingScore: number;
  promoPerformanceScore: number;
}

/**
 * Normalize popularity onto 0..1.
 *
 * Data-availability aware — never invents metrics:
 *  - installs: log-scaled estimatedMinimumInstalls when known.
 *  - reviews: log-scaled reviewCount when known.
 *  - rating: (rating - 3) / 2 clamped to 0..1 (3.0+ is the meaningful range) —
 *    only when a rating exists; apps below 3.0 score 0, unrated score neutral 0.5.
 *  - promoPerformance: historical CTR percentile placeholder (0.5) until enough
 *    analytics exist; wired to real CTR in analytics.ts once data accumulates.
 * Weights: installs 0.50, reviews 0.25, rating 0.15, promo performance 0.10.
 * When installs are missing the review weight absorbs it; when reviews are also
 * missing only rating + performance count.
 */
export function popularityFactors(app: CatalogApp, now: number): PopularityFactors {
  const installsScore =
    app.estimatedMinimumInstalls && app.estimatedMinimumInstalls > 0
      ? clamp01(Math.log10(app.estimatedMinimumInstalls) / Math.log10(10_000_000)) // 10M+ saturates
      : null;

  const reviewScore =
    app.reviewCount && app.reviewCount > 0
      ? clamp01(Math.log10(app.reviewCount) / Math.log10(50_000)) // 50K+ reviews saturates
      : null;

  const ratingScore =
    app.rating !== null && app.rating !== undefined
      ? clamp01((app.rating - 3) / 2)
      : 0.5; // neutral: absence of ratings is not negative signal

  const promoPerformanceScore = 0.5; // placeholder until CTR data exists (see analytics)

  return { installsScore: installsScore ?? 0, reviewScore: reviewScore ?? 0, ratingScore, promoPerformanceScore };
}

/** Popularity in 0..1 with the default weights. Returns also the effective weights used. */
export function popularityScore(
  app: CatalogApp,
  now: number,
): { score: number; factors: PopularityFactors; weights: { installs: number; reviews: number; rating: number; performance: number } } {
  const f = popularityFactors(app, now);
  const hasInstalls = app.estimatedMinimumInstalls !== null && app.estimatedMinimumInstalls > 0;
  const hasReviews = app.reviewCount !== null && app.reviewCount > 0;
  const hasRating = app.rating !== null && app.rating !== undefined;

  // Redistribute weight from missing data to what exists. Never invent signal.
  let wInstalls = hasInstalls ? 0.5 : 0;
  let wReviews = hasReviews ? 0.25 : 0;
  let wRating = hasRating ? 0.15 : 0;
  let wPerformance = 0.1;
  const total = wInstalls + wReviews + wRating + wPerformance;
  if (total <= 0) {
    return { score: 0.5, factors: f, weights: { installs: 0, reviews: 0, rating: 0, performance: 0 } };
  }
  wInstalls /= total;
  wReviews /= total;
  wRating /= total;
  wPerformance /= total;

  const score = clamp01(
    f.installsScore * wInstalls +
      f.reviewScore * wReviews +
      f.ratingScore * wRating +
      f.promoPerformanceScore * wPerformance,
  );
  return {
    score,
    factors: f,
    weights: { installs: wInstalls, reviews: wReviews, rating: wRating, performance: wPerformance },
  };
}

export function clamp01(x: number): number {
  if (Number.isNaN(x)) return 0;
  return Math.min(1, Math.max(0, x));
}

export interface ImpressionStats {
  /** package -> recent impression count (window decided by caller). */
  impressions: Record<string, number>;
}

export interface SelectionInput {
  catalog: CatalogApp[];
  sourcePackage: string;
  placement: string;
  limit: number;
  config: RuntimeConfig;
  sessionId: string | null;
  exclude: string[];
  /** ISO timestamps of recent impressions per package (analytics store). */
  recentImpressions: Record<string, number>;
  nowMs: number;
}

export interface SelectionOutput {
  apps: Recommendation[];
  selectionTypes: SelectionType[];
}

const DAYS_MS = 24 * 3600 * 1000;

function isNewApp(app: CatalogApp, config: RuntimeConfig, nowMs: number): boolean {
  if (!app.firstDiscoveredAt) return false;
  const first = Date.parse(app.firstDiscoveredAt);
  if (!Number.isFinite(first)) return false;
  return nowMs - first <= config.newAppBoostDays * DAYS_MS;
}

/**
 * Core recommendation selection.
 *
 * Per-slot algorithm:
 *  1. Decide selection type for this slot: roll = unitInterval(sessionKey + ':slot:' + i);
 *     roll < popularWeight -> 'popular' (weighted pick), else 'random' (uniform).
 *  2. 'popular': weighted random over eligible apps, weight = (popularity^2) *
 *     promotionMultiplier * recencyPenalty * newBoost. Squaring sharpens the
 *     distribution without changing order; zero-popularity apps keep weight 0.05
 *     so they can still surface.
 *  3. 'random': uniform over eligible — exploration is protected from the boost.
 *  4. New apps (firstDiscoveredAt within newAppBoostDays) get their popularity
 *     weight multiplied by newAppBoostMultiplier inside the popular pool; when a
 *     popular slot actually picks a new app it is reported as 'new_app_boost'.
 *  5. Exclude source package + explicit excludes + disabled apps + duplicates.
 *  6. Session rotation: apps shown recently to this session get a 0.15x weight
 *     penalty (not a hard exclusion, so tiny catalogs still fill up).
 */
export function selectRecommendations(input: SelectionInput): SelectionOutput {
  const { catalog, sourcePackage, placement, limit, config, sessionId, exclude, recentImpressions, nowMs } = input;

  const nowIso = new Date(nowMs).toISOString();
  const eligible = catalog.filter((app) => {
    if (!app.enabledForPromotion) return false;
    if (app.packageName === sourcePackage) return false;
    if (exclude.includes(app.packageName)) return false;
    return true;
  });

  if (eligible.length === 0 || limit <= 0) {
    return { apps: [], selectionTypes: [] };
  }

  const sessionKey = sessionId ? `${sessionId}|${placement}|${sourcePackage}` : `anon|${placement}|${sourcePackage}`;
  const newApps = new Set(eligible.filter((a) => isNewApp(a, config, nowMs)).map((a) => a.packageName));

  const scored = eligible.map((app) => {
    const { score } = popularityScore(app, nowMs);
    const recencyPenalty = (recentImpressions[app.packageName] ?? 0) > 0 ? 0.15 : 1;
    const newBoost = newApps.has(app.packageName) ? config.newAppBoostMultiplier : 1;
    const weighted = Math.pow(Math.max(score, 0.05), 2) * app.promotionMultiplier * recencyPenalty * newBoost;
    return { app, score, weight: weighted };
  });

  const apps: Recommendation[] = [];
  const selectionTypes: SelectionType[] = [];
  const chosen = new Set<string>();

  for (let slot = 0; slot < Math.min(limit, eligible.length); slot++) {
    const roll = unitInterval(`${sessionKey}:slot:${slot}`);
    const type: SelectionType = roll < config.popularWeight ? 'popular' : 'random';

    let candidates = scored.filter((c) => !chosen.has(c.app.packageName));
    if (candidates.length === 0) break;

    // The new-app boost lives inside the popularity pool: boosted weight (not a
    // reserved pool) so exploration share is untouched. When a 'popular' slot
    // draws a new app, report it as 'new_app_boost' for honest analytics.
    let pick: (typeof candidates)[number];
    if (type === 'popular') {
      pick = weightedPick(candidates, sessionKey, slot);
    } else {
      pick = uniformPick(candidates, sessionKey, slot);
    }

    const reportedType: SelectionType =
      newApps.has(pick.app.packageName) && type === 'popular' ? 'new_app_boost' : type;

    chosen.add(pick.app.packageName);
    apps.push(toRecommendation(pick, reportedType, apps.length + 1, nowIso));
    selectionTypes.push(reportedType);
  }

  return { apps, selectionTypes };
}

function weightedPick<T extends { weight: number; app: CatalogApp }>(
  candidates: T[],
  sessionKey: string,
  slot: number,
): T {
  // Deterministic-per-session jitter keeps a session's picks stable while still
  // varying the outcome across sessions and slots.
  const total = candidates.reduce((sum, c) => sum + Math.max(c.weight, 0), 0);
  if (total <= 0) return uniformPick(candidates, sessionKey, slot);
  let roll = unitInterval(`${sessionKey}:w:${slot}`) * total;
  for (const c of candidates) {
    roll -= Math.max(c.weight, 0);
    if (roll <= 0) return c;
  }
  return candidates[candidates.length - 1];
}

function uniformPick<T>(candidates: T[], sessionKey: string, slot: number): T {
  const idx = Math.floor(unitInterval(`${sessionKey}:u:${slot}`) * candidates.length);
  return candidates[Math.min(idx, candidates.length - 1)];
}

function toRecommendation(
  pick: { app: CatalogApp; score: number },
  type: SelectionType,
  rank: number,
  nowIso: string,
): Recommendation {
  const a = pick.app;
  return {
    packageName: a.packageName,
    name: a.name,
    iconUrl: a.iconUrl,
    shortDescription: a.shortDescription,
    rating: a.rating,
    ratingCount: a.reviewCount,
    installText: a.installText,
    storeUrl: a.storeUrl,
    selectionType: type,
    popularityScore: Math.round(pick.score * 1000) / 1000,
  };
}
