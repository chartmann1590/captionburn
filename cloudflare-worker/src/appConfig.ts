/**
 * Per-source-app configuration (D1-backed, remotely editable).
 *
 * Lets the operator disable cross-promotion for one app, cap its cards, restrict
 * placements, or exclude specific target apps — without touching Android clients.
 * D1 failures degrade to defaults: cross promotion must never break the host app.
 */
import type { AppConfig } from './types';
import { DEFAULT_APP_CONFIG } from './types';

export interface ResolvedAppConfig extends AppConfig {
  /** Target packages never to recommend inside this source app. */
  excludedTargets: string[];
}

export const DEFAULT_RESOLVED_APP_CONFIG: ResolvedAppConfig = {
  ...DEFAULT_APP_CONFIG,
  excludedTargets: [],
};

export class D1AppConfigStore {
  constructor(private readonly db: D1Database) {}

  async get(sourcePackage: string): Promise<ResolvedAppConfig> {
    try {
      const row = await this.db
        .prepare(`SELECT enabled, max_cards, placements, excluded_targets FROM app_config WHERE source_package = ?1`)
        .bind(sourcePackage)
        .first<{ enabled: number; max_cards: number; placements: string | null; excluded_targets: string | null }>();
      if (!row) return { ...DEFAULT_RESOLVED_APP_CONFIG };
      return {
        enabled: row.enabled === 1,
        maxCards: row.max_cards ?? DEFAULT_APP_CONFIG.maxCards,
        placements: row.placements ? (JSON.parse(row.placements) as string[]) : null,
        excludedTargets: row.excluded_targets ? (JSON.parse(row.excluded_targets) as string[]) : [],
      };
    } catch {
      return { ...DEFAULT_RESOLVED_APP_CONFIG };
    }
  }

  async put(sourcePackage: string, config: Partial<ResolvedAppConfig>): Promise<void> {
    const current = await this.get(sourcePackage);
    const merged = { ...current, ...config };
    await this.db
      .prepare(
        `INSERT INTO app_config (source_package, enabled, max_cards, placements, excluded_targets, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(source_package) DO UPDATE SET
           enabled = excluded.enabled,
           max_cards = excluded.max_cards,
           placements = excluded.placements,
           excluded_targets = excluded.excluded_targets,
           updated_at = excluded.updated_at`,
      )
      .bind(
        sourcePackage,
        merged.enabled ? 1 : 0,
        merged.maxCards,
        merged.placements ? JSON.stringify(merged.placements) : null,
        JSON.stringify(merged.excludedTargets),
        new Date().toISOString(),
      )
      .run();
  }
}

export function isValidPackageForConfig(pkg: string): boolean {
  return /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$/i.test(pkg) && pkg.length <= 100;
}
