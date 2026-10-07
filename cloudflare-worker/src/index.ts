/**
 * Hartmann Studios Dynamic Cross-Promotion — Cloudflare Worker API.
 *
 * Endpoints (all under /api/v1):
 *   GET  /api/v1/catalog
 *   GET  /api/v1/recommendations
 *   POST /api/v1/events
 *   GET  /api/v1/health
 *   GET  /api/v1/admin/status    (admin token protected)
 *   GET  /api/v1/admin/catalog   (admin token protected)
 *   POST /api/v1/admin/refresh   (admin token protected)
 *   GET  /api/v1/admin/analytics (admin token protected)
 *   POST /api/v1/admin/config    (admin token protected)
 *   POST /api/v1/admin/appconfig (admin token protected)
 *
 * Scheduled handler: cron-triggered catalog refresh with last-known-good safety.
 */
import { KvCatalogStore } from './store';
import { PublicPlayStoreCatalogSource, isValidPackageId } from './playStoreProvider';
import { selectRecommendations } from './ranking';
import { D1AnalyticsStore, KvRateLimiter, validatePromoEvent } from './analytics';
import { D1AppConfigStore, isValidPackageForConfig, type ResolvedAppConfig } from './appConfig';
import {
  DEFAULT_RUNTIME_CONFIG,
  type CatalogSource,
  type RecommendationResult,
  type RuntimeConfig,
} from './types';

export interface Env {
  CROSSPROMO_KV: KVNamespace;
  CROSSPROMO_D1: D1Database;
  ADMIN_TOKEN: string;
  /** Optional authenticated Google Play developer API (future CatalogSource). */
  GOOGLE_SERVICE_ACCOUNT_JSON?: string;
  DEVELOPER_NAME?: string;
  DEVELOPER_PAGE_PATH?: string;
}

const API_VERSION = 1;
const MAX_EVENT_BODY_BYTES = 4_096;

interface RequestContext {
  env: Env;
  store: KvCatalogStore;
  analytics: D1AnalyticsStore;
  appConfigStore: D1AppConfigStore;
  limiter: KvRateLimiter;
  catalogSource: CatalogSource;
  developerName: string;
  /** Runs work after the response is sent (waituntil when available). */
  ctxWaitUntil: (promise: Promise<unknown>) => void;
}

function json(data: unknown, status = 200, cacheControl = 'no-store'): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': cacheControl,
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    },
  });
}

function clientIp(request: Request): string {
  return (
    request.headers.get('cf-connecting-ip') ??
    request.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ??
    'unknown'
  );
}

function isAdminAuthorized(request: Request, env: Env): boolean {
  const header = request.headers.get('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : request.headers.get('x-admin-token');
  return typeof env.ADMIN_TOKEN === 'string' && env.ADMIN_TOKEN.length > 0 && token === env.ADMIN_TOKEN;
}

function catalogSourceFor(env: Env): CatalogSource {
  // CatalogSource priority: authenticated Google source (when configured) ->
  // public Play Store page -> KV last-known-good handled at read time.
  if (env.GOOGLE_SERVICE_ACCOUNT_JSON) {
    // The authenticated source would be implemented here; the public page source
    // below is the default and works without any Google credentials.
    console.log(JSON.stringify({ event: 'google_source_configured_but_not_active', fallback: 'public_play_store' }));
  }
  const developerName = env.DEVELOPER_NAME ?? 'Hartmann Studios';
  const listingPath = env.DEVELOPER_PAGE_PATH ?? '/store/apps/developer?id=Hartmann+Studios';
  return new PublicPlayStoreCatalogSource(developerName, listingPath);
}

function context(request: Request, env: Env, ctx?: ExecutionContext): RequestContext {
  return {
    env,
    store: new KvCatalogStore(env.CROSSPROMO_KV),
    analytics: new D1AnalyticsStore(env.CROSSPROMO_D1),
    appConfigStore: new D1AppConfigStore(env.CROSSPROMO_D1),
    limiter: new KvRateLimiter(env.CROSSPROMO_KV),
    catalogSource: catalogSourceFor(env),
    developerName: env.DEVELOPER_NAME ?? 'Hartmann Studios',
    ctxWaitUntil: (promise: Promise<unknown>) => {
      if (ctx) {
        ctx.waitUntil(promise as Promise<void>);
      } else {
        promise.catch(() => {});
      }
    },
  };
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

async function handleCatalog(ctx: RequestContext): Promise<Response> {
  const catalog = (await ctx.store.getLastKnownGoodCatalog()) ?? (await ctx.store.getCurrentCatalog()) ?? [];
  return json({ version: API_VERSION, generatedAt: new Date().toISOString(), apps: catalog }, 200, 'public, max-age=300');
}

interface RecommendationParams {
  sourcePackage: string;
  placement: string;
  limit: number;
  sessionId: string | null;
  exclude: string[];
  locale: string | null;
  sdkVersion: string | null;
}

function parseRecommendationParams(url: URL): { ok: true; params: RecommendationParams } | { ok: false; error: string } {
  const sourcePackage = url.searchParams.get('sourcePackage') ?? '';
  if (!isValidPackageId(sourcePackage)) return { ok: false, error: 'invalid sourcePackage' };

  const placement = url.searchParams.get('placement') ?? 'default';
  if (!/^[a-zA-Z0-9_-]{1,40}$/.test(placement)) return { ok: false, error: 'invalid placement' };

  const rawLimit = url.searchParams.get('limit');
  let limit = rawLimit === null ? DEFAULT_RUNTIME_CONFIG.defaultLimit : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 1) return { ok: false, error: 'invalid limit' };

  const sessionId = url.searchParams.get('sessionId');
  if (sessionId !== null && !/^[A-Za-z0-9_-]{1,64}$/.test(sessionId)) return { ok: false, error: 'invalid sessionId' };

  const rawExclude = url.searchParams.get('exclude');
  let exclude: string[] = [];
  if (rawExclude) {
    if (rawExclude.length > 1000) return { ok: false, error: 'invalid exclude' };
    exclude = rawExclude
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p.length > 0);
    if (exclude.some((p) => !isValidPackageId(p))) return { ok: false, error: 'invalid exclude package' };
    if (exclude.length > 20) return { ok: false, error: 'too many excludes' };
  }

  const locale = url.searchParams.get('locale');
  if (locale !== null && !/^[a-zA-Z-]{2,10}$/.test(locale)) return { ok: false, error: 'invalid locale' };

  const sdkVersion = url.searchParams.get('sdkVersion');
  if (sdkVersion !== null && sdkVersion.length > 20) return { ok: false, error: 'invalid sdkVersion' };

  return {
    ok: true,
    params: { sourcePackage, placement, limit, sessionId, exclude, locale, sdkVersion },
  };
}

async function handleRecommendations(request: Request, url: URL, ctx: RequestContext): Promise<Response> {
  const parsed = parseRecommendationParams(url);
  if (!parsed.ok) return json({ error: parsed.error }, 400);

  // Per-IP rate limit for recommendations.
  if (!(await ctx.limiter.allow(`rec:${clientIp(request)}`, 120, 60_000))) {
    return json({ error: 'rate_limited' }, 429);
  }

  const config = await ctx.store.getConfig();
  if (!config.enabled) {
    return emptyRecommendations('disabled');
  }

  const params = parsed.params;
  const effectiveLimit = Math.min(params.limit, config.maxLimit);

  // Per-app remote config (kill switch, card caps, placements, target exclusions).
  const appConfig = await ctx.appConfigStore.get(params.sourcePackage);
  if (!appConfig.enabled) {
    return emptyRecommendations('app_disabled');
  }
  if (appConfig.placements && !appConfig.placements.includes(params.placement)) {
    return emptyRecommendations('placement_not_allowed');
  }
  const limit = Math.max(1, Math.min(effectiveLimit, appConfig.maxCards || effectiveLimit));
  const exclude = [...params.exclude, ...appConfig.excludedTargets];

  // One read: serve from last-known-good KV catalog. Never scrape Play on request.
  const catalog = (await ctx.store.getLastKnownGoodCatalog()) ?? (await ctx.store.getCurrentCatalog()) ?? [];
  if (catalog.length === 0) {
    return emptyRecommendations('empty_catalog');
  }

  // Self-healing refresh: if the catalog is stale beyond the refresh interval
  // (e.g. free-plan cron slots exhausted), opportunistically refresh in the
  // background. Clients still get an immediate answer from the cached catalog.
  try {
    const meta = await ctx.store.getRefreshMeta();
    const last = meta.lastSuccessfulRefresh ? Date.parse(meta.lastSuccessfulRefresh) : 0;
    const staleHours = (Date.now() - (Number.isFinite(last) ? last : 0)) / 3_600_000;
    if (staleHours > config.catalogRefreshIntervalHours) {
      ctx.ctxWaitUntil(
        refreshCatalog(ctx).catch(() => undefined),
      );
    }
  } catch {
    // never block a recommendation on the freshness check
  }

  let recentImpressions: Record<string, number> = {};
  try {
    const since = Date.now() - 7 * 24 * 3600 * 1000;
    const counts = await ctx.analytics.recentCounts(since);
    // Starvation guard: only penalize genuinely well-shown apps.
    recentImpressions = counts.impressions;
  } catch {
    recentImpressions = {};
  }

  const selection = selectRecommendations({
    catalog,
    sourcePackage: params.sourcePackage,
    placement: params.placement,
    limit,
    config,
    sessionId: params.sessionId,
    exclude,
    recentImpressions,
    nowMs: Date.now(),
  });

  const nowIso = new Date().toISOString();
  const expiresAt = new Date(Date.now() + config.recommendationTtlSeconds * 1000).toISOString();
  const result: RecommendationResult = {
    version: API_VERSION,
    requestId: crypto.randomUUID(),
    generatedAt: nowIso,
    expiresAt,
    configVersion: config.version,
    apps: selection.apps,
  };
  return json(result, 200, 'public, max-age=60');
}

function emptyRecommendations(reason: string): Response {
  return json(
    {
      version: API_VERSION,
      requestId: crypto.randomUUID(),
      generatedAt: new Date().toISOString(),
      expiresAt: new Date().toISOString(),
      configVersion: DEFAULT_RUNTIME_CONFIG.version,
      apps: [],
      reason,
    },
    200,
    'public, max-age=30',
  );
}

async function handleEvents(request: Request, ctx: RequestContext): Promise<Response> {
  if (request.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);

  // Analytics get a higher but bounded rate limit than recommendations.
  if (!(await ctx.limiter.allow(`events:${clientIp(request)}`, 600, 60_000))) {
    return json({ error: 'rate_limited' }, 429);
  }

  const raw = await request.text();
  if (raw.length === 0 || raw.length > MAX_EVENT_BODY_BYTES) {
    return json({ error: 'invalid_body_size' }, 400);
  }

  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: 'invalid_json' }, 400);
  }

  const catalog = (await ctx.store.getLastKnownGoodCatalog()) ?? (await ctx.store.getCurrentCatalog()) ?? [];
  const catalogPackages = new Set(catalog.map((a) => a.packageName));
  const validated = validatePromoEvent(body, catalogPackages);
  if (!validated.ok) {
    return json({ error: validated.reason }, 422);
  }

  try {
    await ctx.analytics.recordEvent(validated.event, new Date().toISOString());
    return json({ ok: true }, 200);
  } catch (err) {
    console.log(
      JSON.stringify({ event: 'd1_write_failed', errorType: err instanceof Error ? err.name : 'unknown' }),
    );
    return json({ error: 'storage_failed' }, 500);
  }
}

async function handleHealth(ctx: RequestContext): Promise<Response> {
  const [catalog, meta, config] = await Promise.all([
    ctx.store.getLastKnownGoodCatalog(),
    ctx.store.getRefreshMeta(),
    ctx.store.getConfig(),
  ]);
  const now = Date.now();
  const lastSuccess = meta.lastSuccessfulRefresh ? Date.parse(meta.lastSuccessfulRefresh) : null;
  const ageHours = lastSuccess !== null && Number.isFinite(lastSuccess) ? (now - lastSuccess) / 3_600_000 : null;
  const cacheStatus =
    catalog === null ? 'empty' : ageHours === null ? 'unknown' : ageHours <= config.catalogRefreshIntervalHours * 2 ? 'fresh' : 'stale';

  return json(
    {
      status: catalog && catalog.length > 0 ? 'ok' : 'degraded',
      catalogApps: catalog?.length ?? 0,
      lastCatalogRefresh: meta.lastRefreshAttempt,
      lastSuccessfulRefresh: meta.lastSuccessfulRefresh,
      cacheStatus,
    },
    200,
    'public, max-age=60',
  );
}

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

async function handleAdminStatus(ctx: RequestContext): Promise<Response> {
  const [current, lastGood, meta, config, stats] = await Promise.all([
    ctx.store.getCurrentCatalog(),
    ctx.store.getLastKnownGoodCatalog(),
    ctx.store.getRefreshMeta(),
    ctx.store.getConfig(),
    ctx.analytics.summary(),
  ]);
  return json({
    catalogCount: lastGood?.length ?? 0,
    lastRefreshAttempt: meta.lastRefreshAttempt,
    lastSuccessfulRefresh: meta.lastSuccessfulRefresh,
    lastKnownGoodRefresh: meta.lastKnownGoodRefresh,
    lastRejectedReason: meta.lastRejectedReason,
    discoverySource: meta.discoverySource,
    lastMetadataFailures: meta.lastMetadataFailures,
    lastRejectedApps: meta.lastRejectedApps,
    currentCatalogCountMatchesLastGood: (current?.length ?? 0) === (lastGood?.length ?? 0),
    config,
    recommendationsServedNote: 'see analytics for impression/click totals',
    ...stats,
  });
}

async function handleAdminCatalog(ctx: RequestContext): Promise<Response> {
  const catalog = (await ctx.store.getLastKnownGoodCatalog()) ?? [];
  return json({ version: API_VERSION, apps: catalog });
}

async function handleAdminRefresh(ctx: RequestContext): Promise<Response> {
  const result = await refreshCatalog(ctx);
  return json(result.status === 'refreshed' || result.status === 'rejected' ? { ...result } : result, 200);
}

async function handleAdminAnalytics(ctx: RequestContext): Promise<Response> {
  return json(await ctx.analytics.summary());
}

async function handleAdminConfigPost(request: Request, ctx: RequestContext): Promise<Response> {
  let body: Partial<RuntimeConfig>;
  try {
    body = (await request.json()) as Partial<RuntimeConfig>;
  } catch {
    return json({ error: 'invalid_json' }, 400);
  }
  const config = await ctx.store.getConfig();
  const numericKeys: (keyof RuntimeConfig)[] = [
    'popularWeight',
    'randomWeight',
    'newAppBoostDays',
    'newAppBoostMultiplier',
    'defaultLimit',
    'maxLimit',
    'recommendationTtlSeconds',
    'catalogRefreshIntervalHours',
    'metadataRefreshIntervalHours',
    'catalogDropRatioThreshold',
    'version',
  ];
  for (const key of numericKeys) {
    const value = body[key];
    if (value !== undefined && (typeof value !== 'number' || !Number.isFinite(value))) {
      return json({ error: `invalid_${key}` }, 400);
    }
  }
  if (body.enabled !== undefined && typeof body.enabled !== 'boolean') {
    return json({ error: 'invalid_enabled' }, 400);
  }
  const merged: RuntimeConfig = { ...config, ...body };
  // Keep weights coherent.
  const weightSum = merged.popularWeight + merged.randomWeight;
  if (weightSum <= 0 || weightSum > 1.0001) return json({ error: 'invalid_weights' }, 400);
  if (merged.maxLimit < 1 || merged.maxLimit > 20) return json({ error: 'invalid_maxLimit' }, 400);
  await ctx.store.setConfig(merged);
  return json({ ok: true, config: merged });
}

async function handleAdminAppConfig(request: Request, ctx: RequestContext): Promise<Response> {
  let body: { sourcePackage?: string; config?: Partial<ResolvedAppConfig> };
  try {
    body = (await request.json()) as { sourcePackage?: string; config?: Partial<ResolvedAppConfig> };
  } catch {
    return json({ error: 'invalid_json' }, 400);
  }
  const sourcePackage = body.sourcePackage;
  if (typeof sourcePackage !== 'string' || !isValidPackageForConfig(sourcePackage)) {
    return json({ error: 'invalid_sourcePackage' }, 400);
  }
  const partial = body.config ?? {};
  if (partial.enabled !== undefined && typeof partial.enabled !== 'boolean') return json({ error: 'invalid_enabled' }, 400);
  if (partial.maxCards !== undefined && (typeof partial.maxCards !== 'number' || partial.maxCards < 0 || partial.maxCards > 20)) {
    return json({ error: 'invalid_maxCards' }, 400);
  }
  if (partial.placements !== undefined && partial.placements !== null) {
    if (!Array.isArray(partial.placements) || !partial.placements.every((p) => typeof p === 'string' && /^[a-zA-Z0-9_-]{1,40}$/.test(p))) {
      return json({ error: 'invalid_placements' }, 400);
    }
  }
  if (partial.excludedTargets !== undefined && partial.excludedTargets !== null) {
    if (!Array.isArray(partial.excludedTargets) || !partial.excludedTargets.every((p) => typeof p === 'string' && isValidPackageForConfig(p))) {
      return json({ error: 'invalid_excludedTargets' }, 400);
    }
  }
  try {
    await ctx.appConfigStore.put(sourcePackage, partial);
    return json({ ok: true, config: await ctx.appConfigStore.get(sourcePackage) });
  } catch {
    return json({ error: 'storage_failed' }, 500);
  }
}

// ---------------------------------------------------------------------------
// Catalog refresh (cron + admin)
// ---------------------------------------------------------------------------

export interface RefreshOutcome {
  status: 'refreshed' | 'rejected' | 'error';
  detail?: string;
  appCount?: number;
  newApps?: string[];
  removedApps?: string[];
}

export async function refreshCatalog(ctx: RequestContext): Promise<RefreshOutcome> {
  try {
    const discovered = await ctx.catalogSource.discoverApps();
    const result = await ctx.store.commitDiscoveredCatalog(discovered);
    if (result.accepted) {
      console.log(
        JSON.stringify({
          event: 'catalog_refresh_success',
          appCount: result.appCount,
          newApps: result.newApps,
          removedApps: result.removedApps,
          metadataFailures: discovered.metadataFailures.length,
          rejected: discovered.rejectedApps.length,
          source: discovered.source,
        }),
      );
      return { status: 'refreshed', appCount: result.appCount, newApps: result.newApps, removedApps: result.removedApps };
    }
    console.log(
      JSON.stringify({ event: 'catalog_refresh_rejected', reason: result.reason, appCount: result.appCount }),
    );
    return { status: 'rejected', detail: result.reason, appCount: result.appCount };
  } catch (err) {
    console.log(
      JSON.stringify({
        event: 'catalog_refresh_error',
        errorType: err instanceof Error ? err.name : 'unknown',
        detail: err instanceof Error ? err.message.slice(0, 200) : undefined,
      }),
    );
    return { status: 'error', detail: err instanceof Error ? err.message.slice(0, 200) : 'unknown' };
  }
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

function notFound(): Response {
  return json({ error: 'not_found' }, 404);
}

async function route(request: Request, env: Env, execCtx?: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  const ctx = context(request, env, execCtx);

  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: { 'Access-Control-Allow-Origin': '*' } });
  }

  if (!path.startsWith('/api/v1/')) {
    return notFound();
  }

  if (path === '/api/v1/catalog' && request.method === 'GET') return handleCatalog(ctx);
  if (path === '/api/v1/recommendations' && request.method === 'GET') return handleRecommendations(request, url, ctx);
  if (path === '/api/v1/events' && request.method === 'POST') return handleEvents(request, ctx);
  if (path === '/api/v1/health' && request.method === 'GET') return handleHealth(ctx);

  if (path.startsWith('/api/v1/admin/')) {
    if (!isAdminAuthorized(request, env)) return json({ error: 'unauthorized' }, 401);
    if (path === '/api/v1/admin/status' && request.method === 'GET') return handleAdminStatus(ctx);
    if (path === '/api/v1/admin/catalog' && request.method === 'GET') return handleAdminCatalog(ctx);
    if (path === '/api/v1/admin/refresh' && request.method === 'POST') return handleAdminRefresh(ctx);
    if (path === '/api/v1/admin/analytics' && request.method === 'GET') return handleAdminAnalytics(ctx);
    if (path === '/api/v1/admin/config' && request.method === 'POST') return handleAdminConfigPost(request, ctx);
    if (path === '/api/v1/admin/appconfig' && request.method === 'POST') return handleAdminAppConfig(request, ctx);
    return notFound();
  }

  return notFound();
}

export default {
  async fetch(request: Request, env: Env, execCtx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, execCtx);
    } catch (err) {
      console.log(
        JSON.stringify({ event: 'unhandled_error', errorType: err instanceof Error ? err.name : 'unknown' }),
      );
      return json({ error: 'internal_error' }, 500);
    }
  },

  async scheduled(_event: ScheduledController, env: Env, _ctx: ExecutionContext): Promise<void> {
  const ctx = context({} as Request, env);
  const outcome = await refreshCatalog(ctx);
  const config = await ctx.store.getConfig();
  // Also trigger a refresh when the last success is older than the configured
  // interval (covers environments without cron configured).
  const meta = await ctx.store.getRefreshMeta();
  const last = meta.lastSuccessfulRefresh ? Date.parse(meta.lastSuccessfulRefresh) : 0;
  const staleHours = (Date.now() - (Number.isFinite(last) ? last : 0)) / 3_600_000;
  if (outcome.status === 'error' && staleHours > config.catalogRefreshIntervalHours) {
    console.log(JSON.stringify({ event: 'catalog_stale_after_error', staleHours: Math.round(staleHours) }));
  }
  console.log(JSON.stringify({ event: 'cron_refresh', outcome: outcome.status }));
},
} satisfies ExportedHandler<Env>;

// Re-exported for tests.
export { parseRecommendationParams };
export type { ResolvedAppConfig } from './appConfig';
