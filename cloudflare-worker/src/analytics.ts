/**
 * Analytics: D1-backed promo event ingestion + CTR aggregation.
 *
 * Privacy: aggregate promotion performance only. We store package names,
 * placement, event type, and short-lived session ids. No IPs, no device
 * identifiers, no ad IDs.
 *
 * Abuse resistance: events are only accepted for known catalog packages, with
 * strict format limits and a per-IP token-bucket limiter (KV-based), so a
 * malicious client cannot generate millions of D1 writes for free.
 */
import type { PromoEventInput } from './types';

export interface AnalyticsStore {
  recordEvent(event: PromoEventInput, receivedAt: string): Promise<void>;
  /** package -> impression/click counts since `sinceMs`. */
  recentCounts(sinceMs: number): Promise<{
    impressions: Record<string, number>;
    clicks: Record<string, number>;
  }>;
  summary(): Promise<AnalyticsSummary>;
}

export interface AnalyticsSummary {
  totalImpressions: number;
  totalClicks: number;
  overallCtr: number | null;
  byTarget: { targetPackage: string; impressions: number; clicks: number; ctr: number | null }[];
  bySource: { sourcePackage: string; clicks: number }[];
  byPlacement: { placement: string; impressions: number; clicks: number; ctr: number | null }[];
}

const ALLOWED_EVENTS = new Set(['promo_impression', 'promo_click', 'crosspromo_install']);
const PLACEMENT_PATTERN = /^[a-zA-Z0-9_-]{1,40}$/;
const MAX_SESSION_ID_LENGTH = 64;
const MAX_SDK_LENGTH = 20;

export interface ValidatedEvent {
  event: PromoEventInput;
}

/**
 * Validate a client-submitted event against the current catalog.
 * Returns null when the payload must be rejected.
 */
export function validatePromoEvent(
  body: unknown,
  catalogPackages: Set<string>,
): { ok: true; event: PromoEventInput } | { ok: false; reason: string } {
  if (typeof body !== 'object' || body === null) return { ok: false, reason: 'body_not_object' };
  const b = body as Record<string, unknown>;

  const event = b.event;
  if (typeof event !== 'string' || !ALLOWED_EVENTS.has(event)) {
    return { ok: false, reason: 'invalid_event_type' };
  }

  const sourcePackage = b.sourcePackage;
  const targetPackage = b.targetPackage;
  if (typeof sourcePackage !== 'string' || !catalogPackages.has(sourcePackage)) {
    return { ok: false, reason: 'unknown_source_package' };
  }
  if (typeof targetPackage !== 'string' || !catalogPackages.has(targetPackage)) {
    return { ok: false, reason: 'unknown_target_package' };
  }
  if (sourcePackage === targetPackage) {
    return { ok: false, reason: 'source_equals_target' };
  }

  const placement = b.placement;
  if (typeof placement !== 'string' || !PLACEMENT_PATTERN.test(placement)) {
    return { ok: false, reason: 'invalid_placement' };
  }

  const selectionType = b.selectionType;
  if (
    selectionType !== undefined &&
    selectionType !== null &&
    (typeof selectionType !== 'string' || !['popular', 'random', 'new_app_boost'].includes(selectionType))
  ) {
    return { ok: false, reason: 'invalid_selection_type' };
  }

  const requestId = b.recommendationRequestId;
  if (
    requestId !== undefined &&
    requestId !== null &&
    (typeof requestId !== 'string' || requestId.length > 64)
  ) {
    return { ok: false, reason: 'invalid_request_id' };
  }

  const sessionId = b.sessionId;
  if (sessionId !== undefined && sessionId !== null) {
    if (typeof sessionId !== 'string' || sessionId.length > MAX_SESSION_ID_LENGTH) {
      return { ok: false, reason: 'invalid_session_id' };
    }
  }

  const rankPosition = b.rankPosition;
  if (rankPosition !== undefined && rankPosition !== null) {
    if (typeof rankPosition !== 'number' || !Number.isInteger(rankPosition) || rankPosition < 1 || rankPosition > 50) {
      return { ok: false, reason: 'invalid_rank_position' };
    }
  }

  const sdkVersion = b.sdkVersion;
  if (sdkVersion !== undefined && sdkVersion !== null) {
    if (typeof sdkVersion !== 'string' || sdkVersion.length > MAX_SDK_LENGTH) {
      return { ok: false, reason: 'invalid_sdk_version' };
    }
  }

  return {
    ok: true,
    event: {
      event: event as PromoEventInput['event'],
      sourcePackage,
      targetPackage,
      placement,
      selectionType: typeof selectionType === 'string' ? selectionType : null,
      recommendationRequestId: typeof requestId === 'string' ? requestId : null,
      sessionId: typeof sessionId === 'string' ? sessionId : null,
      rankPosition: typeof rankPosition === 'number' ? rankPosition : null,
      sdkVersion: typeof sdkVersion === 'string' ? sdkVersion : null,
    },
  };
}

/** Simple per-IP rate limiter on KV with per-minute fixed windows. */
export class KvRateLimiter {
  constructor(
    private readonly kv: KVNamespace,
    private readonly nowMs: () => number = () => Date.now(),
  ) {}

  /**
   * @param key logical bucket, e.g. "rec" or "events"
   * @param limit max requests per window
   * @param windowMs window length
   * @returns true when allowed
   */
  async allow(key: string, limit: number, windowMs: number): Promise<boolean> {
    const window = Math.floor(this.nowMs() / windowMs);
    const kvKey = `rl:${key}:${window}`;
    const current = (await this.kv.get<number>(kvKey, 'json')) ?? 0;
    if (current >= limit) return false;
    // KV writes are eventually consistent; slight over-admission under a burst is
    // acceptable for abuse resistance (not billing protection at this scale).
    await this.kv.put(kvKey, JSON.stringify(current + 1), { expirationTtl: Math.ceil((windowMs * 2) / 1000) });
    return true;
  }
}

/** D1 implementation of AnalyticsStore. */
export class D1AnalyticsStore implements AnalyticsStore {
  constructor(private readonly db: D1Database) {}

  async recordEvent(event: PromoEventInput, receivedAt: string): Promise<void> {
    await this.db
      .prepare(
        `INSERT INTO promo_events
           (event_type, source_package, target_package, placement, selection_type,
            recommendation_request_id, session_id, rank_position, sdk_version, received_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)`,
      )
      .bind(
        event.event,
        event.sourcePackage,
        event.targetPackage,
        event.placement,
        event.selectionType,
        event.recommendationRequestId,
        event.sessionId,
        event.rankPosition,
        event.sdkVersion,
        receivedAt,
      )
      .run();
  }

  async recentCounts(sinceMs: number): Promise<{ impressions: Record<string, number>; clicks: Record<string, number> }> {
    const sinceIso = new Date(sinceMs).toISOString();
    const impressions: Record<string, number> = {};
    const clicks: Record<string, number> = {};
    try {
      const rows = await this.db
        .prepare(
          `SELECT target_package, event_type, COUNT(*) AS n
             FROM promo_events
            WHERE received_at >= ?1 AND event_type IN ('promo_impression', 'promo_click')
            GROUP BY target_package, event_type`,
        )
        .bind(sinceIso)
        .all<{ target_package: string; event_type: string; n: number }>();
      for (const row of rows.results ?? []) {
        if (row.event_type === 'promo_impression') impressions[row.target_package] = row.n;
        else clicks[row.target_package] = row.n;
      }
    } catch {
      // Analytics must never break recommendations.
    }
    return { impressions, clicks };
  }

  async summary(): Promise<AnalyticsSummary> {
    const empty: AnalyticsSummary = {
      totalImpressions: 0,
      totalClicks: 0,
      overallCtr: null,
      byTarget: [],
      bySource: [],
      byPlacement: [],
    };
    try {
      const totals = await this.db
        .prepare(
          `SELECT
             SUM(CASE WHEN event_type = 'promo_impression' THEN 1 ELSE 0 END) AS impressions,
             SUM(CASE WHEN event_type = 'promo_click' THEN 1 ELSE 0 END) AS clicks
           FROM promo_events`,
        )
        .first<{ impressions: number | null; clicks: number | null }>();
      const totalImpressions = totals?.impressions ?? 0;
      const totalClicks = totals?.clicks ?? 0;

      const byTarget = await this.db
        .prepare(
          `SELECT target_package,
                  SUM(CASE WHEN event_type = 'promo_impression' THEN 1 ELSE 0 END) AS impressions,
                  SUM(CASE WHEN event_type = 'promo_click' THEN 1 ELSE 0 END) AS clicks
             FROM promo_events
            GROUP BY target_package
            ORDER BY clicks DESC, impressions DESC
            LIMIT 100`,
        )
        .all<{ target_package: string; impressions: number; clicks: number }>();

      const bySource = await this.db
        .prepare(
          `SELECT source_package, COUNT(*) AS clicks
             FROM promo_events
            WHERE event_type IN ('promo_click', 'crosspromo_install')
            GROUP BY source_package
            ORDER BY clicks DESC
            LIMIT 100`,
        )
        .all<{ source_package: string; clicks: number }>();

      const byPlacement = await this.db
        .prepare(
          `SELECT placement,
                  SUM(CASE WHEN event_type = 'promo_impression' THEN 1 ELSE 0 END) AS impressions,
                  SUM(CASE WHEN event_type = 'promo_click' THEN 1 ELSE 0 END) AS clicks
             FROM promo_events
            GROUP BY placement
            ORDER BY impressions DESC
            LIMIT 50`,
        )
        .all<{ placement: string; impressions: number; clicks: number }>();

      const ctr = (c: number, i: number): number | null => (i > 0 ? Math.round((c / i) * 10000) / 10000 : null);

      return {
        totalImpressions,
        totalClicks,
        overallCtr: ctr(totalClicks, totalImpressions),
        byTarget: (byTarget.results ?? []).map((r) => ({
          targetPackage: r.target_package,
          impressions: r.impressions,
          clicks: r.clicks,
          ctr: ctr(r.clicks, r.impressions),
        })),
        bySource: (bySource.results ?? []).map((r) => ({ sourcePackage: r.source_package, clicks: r.clicks })),
        byPlacement: (byPlacement.results ?? []).map((r) => ({
          placement: r.placement,
          impressions: r.impressions,
          clicks: r.clicks,
          ctr: ctr(r.clicks, r.impressions),
        })),
      };
    } catch {
      return empty;
    }
  }
}
