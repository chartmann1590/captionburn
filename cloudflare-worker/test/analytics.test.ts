import { describe, expect, it } from 'vitest';
import { KvRateLimiter, validatePromoEvent } from '../src/analytics';

const catalogPackages = new Set(['com.a.source', 'com.b.target', 'com.c.other']);

function validEvent(): Record<string, unknown> {
  return {
    event: 'promo_click',
    sourcePackage: 'com.a.source',
    targetPackage: 'com.b.target',
    placement: 'settings',
    selectionType: 'popular',
    recommendationRequestId: 'req-123',
    sessionId: 'sess-abc',
    rankPosition: 1,
    sdkVersion: '1.0.0',
  };
}

describe('validatePromoEvent', () => {
  it('accepts a valid event', () => {
    const result = validatePromoEvent(validEvent(), catalogPackages);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.event.event).toBe('promo_click');
      expect(result.event.rankPosition).toBe(1);
    }
  });

  it('rejects unknown event types', () => {
    const e = { ...validEvent(), event: 'pageview' };
    expect(validatePromoEvent(e, catalogPackages).ok).toBe(false);
  });

  it('rejects packages not in the Hartmann catalog', () => {
    const e = { ...validEvent(), targetPackage: 'com.not.hartmann' };
    const result = validatePromoEvent(e, catalogPackages);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('unknown_target_package');
  });

  it('rejects source == target', () => {
    const e = { ...validEvent(), targetPackage: 'com.a.source' };
    const result = validatePromoEvent(e, catalogPackages);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('source_equals_target');
  });

  it('rejects invalid placements and oversized fields', () => {
    expect(validatePromoEvent({ ...validEvent(), placement: 'bad placement!!' }, catalogPackages).ok).toBe(false);
    expect(validatePromoEvent({ ...validEvent(), sessionId: 'x'.repeat(100) }, catalogPackages).ok).toBe(false);
    expect(validatePromoEvent({ ...validEvent(), rankPosition: 0 }, catalogPackages).ok).toBe(false);
    expect(validatePromoEvent({ ...validEvent(), rankPosition: 99 }, catalogPackages).ok).toBe(false);
    expect(validatePromoEvent({ ...validEvent(), recommendationRequestId: 'x'.repeat(65) }, catalogPackages).ok).toBe(false);
  });

  it('rejects non-object bodies', () => {
    expect(validatePromoEvent(null, catalogPackages).ok).toBe(false);
    expect(validatePromoEvent('hello', catalogPackages).ok).toBe(false);
    expect(validatePromoEvent([], catalogPackages).ok).toBe(false);
  });

  it('allows optional fields to be absent', () => {
    const { selectionType, recommendationRequestId, sessionId, rankPosition, sdkVersion, ...minimal } = validEvent();
    const result = validatePromoEvent(minimal, catalogPackages);
    expect(result.ok).toBe(true);
  });
});

describe('KvRateLimiter', () => {
  function kvStub(): KVNamespace {
    const map = new Map<string, unknown>();
    return {
      get: async (key: string) => map.get(key) ?? null,
      put: async (key: string, value: unknown, _opts?: unknown) => {
        map.set(key, JSON.parse(value as string));
      },
    } as unknown as KVNamespace;
  }

  it('allows up to the limit then blocks', async () => {
    const limiter = new KvRateLimiter(kvStub(), () => 1_000_000);
    const results: boolean[] = [];
    for (let i = 0; i < 5; i++) {
      results.push(await limiter.allow('ip:1.2.3.4', 3, 60_000));
    }
    expect(results).toEqual([true, true, true, false, false]);
  });

  it('resets after the window passes', async () => {
    let now = 1_000_000;
    const limiter = new KvRateLimiter(kvStub(), () => now);
    expect(await limiter.allow('ip:1.2.3.4', 1, 60_000)).toBe(true);
    expect(await limiter.allow('ip:1.2.3.4', 1, 60_000)).toBe(false);
    now += 61_000;
    expect(await limiter.allow('ip:1.2.3.4', 1, 60_000)).toBe(true);
  });

  it('tracks different keys independently', async () => {
    const limiter = new KvRateLimiter(kvStub(), () => 1_000_000);
    expect(await limiter.allow('ip:a', 1, 60_000)).toBe(true);
    expect(await limiter.allow('ip:b', 1, 60_000)).toBe(true);
    expect(await limiter.allow('ip:a', 1, 60_000)).toBe(false);
  });
});
