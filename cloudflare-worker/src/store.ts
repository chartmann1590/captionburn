/**
 * Cloudflare KV catalog/config persistence + validation-aware refresh.
 *
 * Catalog safety: a refresh result is compared against the current catalog and is
 * only promoted to `current` (and `lastKnownGood`) when it passes sanity checks.
 * A refresh that discovers 0 apps, or a dramatic unexplained drop, is rejected and
 * diagnostics are recorded instead of wiping promotions.
 */
import type { CatalogApp, CatalogRefreshMeta, DiscoveredCatalog, RuntimeConfig } from './types';
import { DEFAULT_RUNTIME_CONFIG } from './types';

export interface CatalogStore {
  getCurrentCatalog(): Promise<CatalogApp[] | null>;
  getLastKnownGoodCatalog(): Promise<CatalogApp[] | null>;
  getRefreshMeta(): Promise<CatalogRefreshMeta>;
  /** Validate + write a freshly discovered catalog. Returns whether it was accepted. */
  commitDiscoveredCatalog(discovered: DiscoveredCatalog): Promise<{
    accepted: boolean;
    reason?: string;
    appCount: number;
    newApps: string[];
    removedApps: string[];
  }>;
  getConfig(): Promise<RuntimeConfig>;
  setConfig(config: RuntimeConfig): Promise<void>;
}

const KEY_CURRENT = 'catalog:current';
const KEY_LAST_GOOD = 'catalog:lastKnownGood';
const KEY_REFRESH_META = 'catalog:refreshMeta';
const KEY_CONFIG = 'config:runtime';

export class KvCatalogStore implements CatalogStore {
  constructor(private readonly kv: KVNamespace) {}

  async getCurrentCatalog(): Promise<CatalogApp[] | null> {
    return (await this.kv.get<CatalogApp[]>(KEY_CURRENT, 'json')) ?? null;
  }

  async getLastKnownGoodCatalog(): Promise<CatalogApp[] | null> {
    return (await this.kv.get<CatalogApp[]>(KEY_LAST_GOOD, 'json')) ?? null;
  }

  async getRefreshMeta(): Promise<CatalogRefreshMeta> {
    const stored = await this.kv.get<CatalogRefreshMeta>(KEY_REFRESH_META, 'json');
    return (
      stored ?? {
        lastRefreshAttempt: null,
        lastSuccessfulRefresh: null,
        lastKnownGoodRefresh: null,
        lastSuccessfulAppCount: null,
        lastRejectedReason: null,
        discoverySource: null,
        lastMetadataFailures: 0,
        lastRejectedApps: 0,
      }
    );
  }

  async commitDiscoveredCatalog(discovered: DiscoveredCatalog): Promise<{
    accepted: boolean;
    reason?: string;
    appCount: number;
    newApps: string[];
    removedApps: string[];
  }> {
    const now = new Date().toISOString();
    const current = await this.getCurrentCatalog();
    const meta = await this.getRefreshMeta();

    const attemptMeta: CatalogRefreshMeta = {
      ...meta,
      lastRefreshAttempt: now,
      discoverySource: discovered.source,
      lastMetadataFailures: discovered.metadataFailures.length,
      lastRejectedApps: discovered.rejectedApps.length,
    };

    const validate = validateCatalogRefresh(current, discovered.apps);
    if (!validate.accepted) {
      const rejectedMeta: CatalogRefreshMeta = {
        ...attemptMeta,
        lastRejectedReason: validate.reason ?? 'unknown',
      };
      await this.kv.put(KEY_REFRESH_META, JSON.stringify(rejectedMeta));
      return {
        accepted: false,
        reason: validate.reason,
        appCount: discovered.apps.length,
        newApps: validate.newApps,
        removedApps: validate.removedApps,
      };
    }

    // Carry forward history-aware fields from the existing catalog:
    // firstDiscoveredAt must survive refreshes, enabledForPromotion and
    // promotionMultiplier are server-controlled state that must not reset.
    const previousByPkg = new Map((current ?? []).map((a) => [a.packageName, a]));
    const merged = discovered.apps.map((app) => {
      const prev = previousByPkg.get(app.packageName);
      return {
        ...app,
        firstDiscoveredAt: prev?.firstDiscoveredAt ?? app.firstDiscoveredAt,
        enabledForPromotion: prev?.enabledForPromotion ?? app.enabledForPromotion,
        promotionMultiplier: prev?.promotionMultiplier ?? app.promotionMultiplier,
      };
    });

    await this.kv.put(KEY_CURRENT, JSON.stringify(merged));
    await this.kv.put(KEY_LAST_GOOD, JSON.stringify(merged));
    const acceptedMeta: CatalogRefreshMeta = {
      ...attemptMeta,
      lastSuccessfulRefresh: now,
      lastKnownGoodRefresh: now,
      lastSuccessfulAppCount: merged.length,
      lastRejectedReason: null,
    };
    await this.kv.put(KEY_REFRESH_META, JSON.stringify(acceptedMeta));
    return {
      accepted: true,
      appCount: merged.length,
      newApps: validate.newApps,
      removedApps: validate.removedApps,
    };
  }

  async getConfig(): Promise<RuntimeConfig> {
    const stored = await this.kv.get<Partial<RuntimeConfig>>(KEY_CONFIG, 'json');
    return { ...DEFAULT_RUNTIME_CONFIG, ...(stored ?? {}) };
  }

  async setConfig(config: RuntimeConfig): Promise<void> {
    await this.kv.put(KEY_CONFIG, JSON.stringify(config));
  }
}

export interface RefreshValidation {
  accepted: boolean;
  reason?: string;
  newApps: string[];
  removedApps: string[];
}

/**
 * Decide whether a freshly discovered catalog may replace the current one.
 *
 * Rules:
 *  - 0 apps discovered -> reject (Google HTML change or outage; never wipe).
 *  - previous existed and new count < previous * dropRatioThreshold -> reject as
 *    suspicious (unless virtually all previously-known apps are still present,
 *    which would indicate a legitimate intentional removal of most apps — but we
 *    still conservatively reject a >threshold drop and require manual review).
 *  - otherwise accept, reporting new/removed packages for diagnostics.
 */
export function validateCatalogRefresh(
  current: CatalogApp[] | null,
  discovered: CatalogApp[],
  dropRatioThreshold = DEFAULT_RUNTIME_CONFIG.catalogDropRatioThreshold,
): RefreshValidation {
  const currentPkgs = new Set((current ?? []).map((a) => a.packageName));
  const discoveredPkgs = new Set(discovered.map((a) => a.packageName));

  const newApps = [...discoveredPkgs].filter((p) => !currentPkgs.has(p));
  const removedApps = [...currentPkgs].filter((p) => !discoveredPkgs.has(p));

  if (discovered.length === 0) {
    return { accepted: false, reason: 'zero_apps_discovered', newApps, removedApps };
  }

  if (current && current.length > 0) {
    const ratio = discovered.length / current.length;
    if (ratio < dropRatioThreshold) {
      return {
        accepted: false,
        reason: `suspicious_catalog_drop previous=${current.length} discovered=${discovered.length}`,
        newApps,
        removedApps,
      };
    }
  }

  return { accepted: true, newApps, removedApps };
}
