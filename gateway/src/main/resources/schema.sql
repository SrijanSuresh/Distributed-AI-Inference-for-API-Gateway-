create table if not exists inference_log (
  id serial primary key,
  ts timestamptz default now(),
  model text not null,
  text_hash text not null,
  label text not null,
  score double precision not null,
  latency_ms integer not null,
  cache_hit boolean not null,
  worker_id text not null
);
