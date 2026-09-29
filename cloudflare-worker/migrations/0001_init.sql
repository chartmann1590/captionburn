-- Hartmann Cross-Promo: initial schema.
-- Aggregate promotion analytics only; no user identifiers.
CREATE TABLE IF NOT EXISTS promo_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_type TEXT NOT NULL CHECK (event_type IN ('promo_impression', 'promo_click', 'crosspromo_install')),
  source_package TEXT NOT NULL,
  target_package TEXT NOT NULL,
  placement TEXT NOT NULL,
  selection_type TEXT,
  recommendation_request_id TEXT,
  session_id TEXT,
  rank_position INTEGER,
  sdk_version TEXT,
  received_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_promo_events_target_time ON promo_events (target_package, received_at);
CREATE INDEX IF NOT EXISTS idx_promo_events_type_time ON promo_events (event_type, received_at);
CREATE INDEX IF NOT EXISTS idx_promo_events_source ON promo_events (source_package);

CREATE TABLE IF NOT EXISTS app_config (
  source_package TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 1,
  max_cards INTEGER NOT NULL DEFAULT 3,
  placements TEXT,
  excluded_targets TEXT NOT NULL DEFAULT '[]',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS catalog_refreshes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  attempted_at TEXT NOT NULL,
  source TEXT,
  status TEXT NOT NULL CHECK (status IN ('accepted', 'rejected', 'error')),
  app_count INTEGER,
  detail TEXT
);
