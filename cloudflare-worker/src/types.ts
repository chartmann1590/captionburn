/**
 * Normalized internal model for a promotable Hartmann Studios app.
 *
 * This is the ONLY shape the rest of the system is allowed to depend on.
 * Google Play HTML never leaks past the PlayStoreCatalogProvider.
 *
 * Nullable fields are optional by design: one missing metadata field must never
 * fail the whole catalog or exclude an app from promotion.
 */

/** A catalog entry as discovered and normalized from a CatalogSource. */
export interface CatalogApp {
  packageName: string;
  name: string;
  iconUrl: string | null;
  storeUrl: string;
  shortDescription: string | null;
  fullDescription: string | null;
  /** 0..5 star average, null when the app has too few ratings for Play to show one. */
  rating: number | null;
  /** Number of ratings/reviews shown on the details page, null when unavailable. */
  reviewCount: number | null;
  /** Raw Play install label, e.g. "100K+". Null when unavailable. */
  installText: string | null;
  /** Lower bound implied by installText (100K+ -> 100000). Null when unparseable. */
  estimatedMinimumInstalls: number | null;
  /** Play category label, e.g. "Video Players & Editors". */
  category: string | null;
  priceText: string | null;
  isFree: boolean;
  developer: string | null;
  /** ISO timestamp of the most successful metadata refresh for this app. */
  lastMetadataRefresh: string | null;
  /** ISO timestamp when this package first appeared in any successful refresh. */
  firstDiscoveredAt: string | null;
  /** ISO timestamp this app was last seen on the developer page. */
  lastSeenAt: string | null;
  /** Server-side kill switch / per-app toggle. Default true. */
  enabledForPromotion: boolean;
  /** Optional manual ranking multiplier (>= 0). Default 1. Backend-configured only. */
  promotionMultiplier: number;
}

/** What a CatalogSource returns for a full discovery pass. */
export interface DiscoveredCatalog {
  apps: CatalogApp[];
  source: string;
  fetchedAt: string;
  /** Non-fatal per-app problems encountered during metadata enrichment. */
  metadataFailures: { packageName: string; error: string }[];
  /** Apps seen on the listing page but rejected by validation. */
  rejectedApps: { packageName: string; reason: string }[];
}

export interface CatalogSource {
  readonly name: string;
  discoverApps(): Promise<DiscoveredCatalog>;
}

/** One recommendation returned to a client. Kept deliberately small. */
export interface Recommendation {
  packageName: string;
  name: string;
  iconUrl: string | null;
  shortDescription: string | null;
  rating: number | null;
  ratingCount: number | null;
  installText: string | null;
  storeUrl: string;
  selectionType: SelectionType;
  /** Popularity score 0..1 (pre-multiplier), for debugging/observability. */
  popularityScore: number;
}

export type SelectionType = 'popular' | 'random' | 'new_app_boost';

export interface RecommendationResult {
  version: number;
  apps: Recommendation[];
  requestId: string;
  generatedAt: string;
  expiresAt: string;
  configVersion: number;
  /** Present only when the response is empty: why (disabled, empty_catalog, ...). */
  reason?: string;
}

/** Analytics events accepted from clients. */
export type PromoEventType = 'promo_impression' | 'promo_click' | 'crosspromo_install';

export interface PromoEventInput {
  event: PromoEventType;
  sourcePackage: string;
  targetPackage: string;
  placement: string;
  selectionType?: string | null;
  recommendationRequestId?: string | null;
  sessionId?: string | null;
  rankPosition?: number | null;
  sdkVersion?: string | null;
}

/** Persisted alongside the catalog to power catalog safety checks. */
export interface CatalogRefreshMeta {
  lastRefreshAttempt: string | null;
  lastSuccessfulRefresh: string | null;
  lastKnownGoodRefresh: string | null;
  lastSuccessfulAppCount: number | null;
  lastRejectedReason: string | null;
  discoverySource: string | null;
  lastMetadataFailures: number;
  lastRejectedApps: number;
}

/** Top-level runtime configuration (remote-controlled, no Android releases needed). */
export interface RuntimeConfig {
  version: number;
  enabled: boolean;
  popularWeight: number;
  randomWeight: number;
  newAppBoostDays: number;
  newAppBoostMultiplier: number;
  defaultLimit: number;
  maxLimit: number;
  /** Cache TTL for recommendations in seconds. */
  recommendationTtlSeconds: number;
  catalogRefreshIntervalHours: number;
  metadataRefreshIntervalHours: number;
  /** Suspicious-refresh detection: reject when new count < previous * this. */
  catalogDropRatioThreshold: number;
}

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  version: 1,
  enabled: true,
  popularWeight: 0.65,
  randomWeight: 0.35,
  newAppBoostDays: 14,
  newAppBoostMultiplier: 4,
  defaultLimit: 3,
  maxLimit: 6,
  recommendationTtlSeconds: 6 * 3600,
  catalogRefreshIntervalHours: 6,
  metadataRefreshIntervalHours: 12,
  catalogDropRatioThreshold: 0.5,
};

/** Per-source-app configuration (remote kill switch, card limits, placements). */
export interface AppConfig {
  enabled: boolean;
  maxCards: number;
  placements: string[] | null;
}

export const DEFAULT_APP_CONFIG: AppConfig = {
  enabled: true,
  maxCards: 3,
  placements: null,
};
