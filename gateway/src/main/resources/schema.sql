CREATE TABLE IF NOT EXISTS inference_log (
  id          BIGSERIAL PRIMARY KEY,
  ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  model       TEXT        NOT NULL,
  text_hash   CHAR(32)    NOT NULL,        -- md5 hex (32 chars)
  label       TEXT        NOT NULL,
  score       DOUBLE PRECISION NOT NULL,
  latency_ms  BIGINT      NOT NULL,        -- end-to-end latency measured at gateway
  cache_hit   BOOLEAN     NOT NULL,
  worker_id   TEXT        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inference_log_ts         ON inference_log (ts DESC);
CREATE INDEX IF NOT EXISTS idx_inference_log_model      ON inference_log (model);
CREATE INDEX IF NOT EXISTS idx_inference_log_text_hash  ON inference_log (text_hash);
