# Distributed AI Inference Gateway  
_Spring Boot + gRPC + Redis + Postgres_

A lightweight, production-style setup for serving AI inference through a single HTTP API:

- **Gateway (HTTP/REST)** receives `/api/v1/classify`
- **Redis** caches repeat inferences (fast path)
- **Worker (gRPC)** performs the model inference (Hugging Face sentiment with safe fallback)
- **Postgres** stores an audit log of every request/response/latency

MongoDB was intentionally replaced with **Postgres**.

---

## Why this exists

- **One clean API** for inference across models
- **Low latency** on repeated inputs via Redis caching
- **Auditability** via Postgres logs (latency, labels, cache hits, worker id)
- **Swap models without breaking the API** (model lives behind the gRPC worker)

---

## Architecture

      HTTP (JSON)

Client ─────────────→ Gateway :8081
│
(cache hit?) ─┐ │ (cache miss)
└──↓───────────── gRPC
Redis :6379 Worker :9090
│ │
└──────→ Postgres :5430 (audit log)

- Cache key: `model::md5(text)`
- Table: `inference_log(ts, model, text_hash, label, score, latency_ms, cache_hit, worker_id)`

---

## Tech

- **Java 21**, Spring Boot 3
- **gRPC** (protobuf) between gateway and worker
- **Redis** for caching
- **Postgres** for durable logging
- **Hugging Face Inference API** (optional) for real sentiment model in the worker

---

## Quick Start (Dev)

> Works on Windows with **Docker Desktop** + **Git Bash** or **PowerShell**.  
> We use **host port 5430** for Postgres to avoid common collisions.

### 1) Start Redis & Postgres (Docker)

```bash
# Redis
docker run -d --name redis -p 6379:6379 redis:7

# Postgres (host 5430 -> container 5432)
docker rm -f pg 2>/dev/null || true
docker run --name pg \
  -e POSTGRES_PASSWORD=postgres \
  -p 5430:5432 \
  -d postgres:16

# Quick check (should return "ok = 1")
docker exec -e PGPASSWORD=postgres -it pg psql -U postgres -c "select 1 as ok;"
```

2) Run the Worker (gRPC :9090)

Git Bash

```bash
# Optional: real model (Hugging Face) token
export HF_TOKEN=hf_xxx_your_token_here

cd worker
./mvnw -DskipTests=true spring-boot:run
```

PowerShell

```powershell
$env:HF_TOKEN="hf_xxx_your_token_here"
Set-Location .\worker
.\mvnw.cmd -DskipTests=true spring-boot:run
```

3) Run the Gateway (HTTP :8081)

gateway/src/main/resources/application.properties:

```
server.port=8081

spring.redis.host=127.0.0.1
spring.redis.port=6379

spring.datasource.url=jdbc:postgresql://127.0.0.1:5430/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.sql.init.mode=always
```

Start it:

Git Bash

```bash
cd gateway
WORKER_ADDR=127.0.0.1:9090 ./mvnw -DskipTests=true spring-boot:run
```

PowerShell

```powershell
Set-Location .\gateway
$env:WORKER_ADDR="127.0.0.1:9090"
.\mvnw.cmd -DskipTests=true spring-boot:run
```

Try It

First call (cache miss → gRPC → DB)

```bash
curl -s -X POST http://localhost:8081/api/v1/classify \
  -H "Content-Type: application/json" \
  -d '{"model":"sentiment","text":"i love this project"}'
```

Second call (cache hit → Redis)

```bash
curl -s -X POST http://localhost:8081/api/v1/classify \
  -H "Content-Type: application/json" \
  -d '{"model":"sentiment","text":"i love this project"}'
```

See recent rows in Postgres

```bash
docker exec -e PGPASSWORD=postgres -it pg psql -U postgres -c \
"select ts, model, label, score, latency_ms, cache_hit, worker_id
 from inference_log order by id desc limit 5;"
```

Expected:

1st response: "cacheHit": false (slower, goes to worker)

2nd response: "cacheHit": true (fast, from Redis)

Both appear in inference_log

API

POST /api/v1/classify

Request

```
{
  "model": "sentiment",
  "text": "i love this project"
}
```

Response

```
{
  "label": "POSITIVE",
  "score": 0.98,
  "latencyMs": 12,
  "workerId": "worker-abc123",
  "cacheHit": true
}
```

Key Files
gateway/
  src/main/java/com/ss/gateway/api/InferenceController.java
  src/main/java/com/ss/gateway/db/LoggingService.java
  src/main/resources/application.properties
  src/main/resources/schema.sql        # creates inference_log

worker/
  src/main/java/com/ss/worker/grpc/InferenceService.java  # model call + fallback
  src/main/proto/infer.proto

Notes

If you mapped Postgres to a different host port, adjust the JDBC URL accordingly:
jdbc:postgresql://127.0.0.1:<HOST_PORT>/postgres

The worker calls a real sentiment model if HF_TOKEN is set; otherwise it falls back to a simple heuristic so the system always works.

Troubleshooting

Port already in use

Change the host port (e.g., -p 5430:5432 for Postgres).

Check owners on Windows:

netstat -ano | findstr :5430
tasklist /FI "PID eq <pid>"

Auth errors with Postgres

You may be reusing an old volume with a different password. Start fresh:

docker rm -f pg
docker volume rm pgdata_dev 2>/dev/null || true
docker run --name pg -e POSTGRES_PASSWORD=postgres -p 5430:5432 -v pgdata_dev:/var/lib/postgresql/data -d postgres:16

Clean Java build if IDE is confused

./mvnw -DskipTests=true clean package


### Metrics Snapshot (dev)

- **Requests:** 3  
- **Cache hits / misses:** 2 / 1 → **Hit ratio: 66.7%**  
- **Latency (avg / max):** **603 ms** / **1,789 ms**  
- Source: `GET /actuator/prometheus`

<details>
<summary>Raw Prometheus lines</summary>

inference_requests_total 3.0
inference_cache_hits_total 2.0
inference_cache_misses_total 1.0
inference_latency_seconds_count 3
inference_latency_seconds_sum 1.809854
inference_latency_seconds_max 1.7887968

</details>

**Metric Full-View:**
```
$ curl -s http://localhost:8081/actuator/prometheus | grep -E "inference_|process_uptime"
# HELP inference_cache_hits_total Cache hits
# TYPE inference_cache_hits_total counter
inference_cache_hits_total 2.0
# HELP inference_cache_misses_total Cache misses
# TYPE inference_cache_misses_total counter
inference_cache_misses_total 1.0
# HELP inference_latency_seconds End-to-end request latency
# TYPE inference_latency_seconds histogram
inference_latency_seconds_bucket{le="0.001"} 0
inference_latency_seconds_bucket{le="0.001048576"} 0
inference_latency_seconds_bucket{le="0.001398101"} 0
inference_latency_seconds_bucket{le="0.001747626"} 0
inference_latency_seconds_bucket{le="0.002097151"} 0
inference_latency_seconds_bucket{le="0.002446676"} 0
inference_latency_seconds_bucket{le="0.002796201"} 0
inference_latency_seconds_bucket{le="0.003145726"} 0
inference_latency_seconds_bucket{le="0.003495251"} 0
inference_latency_seconds_bucket{le="0.003844776"} 0
inference_latency_seconds_bucket{le="0.004194304"} 0
inference_latency_seconds_bucket{le="0.005592405"} 0
inference_latency_seconds_bucket{le="0.006990506"} 0
inference_latency_seconds_bucket{le="0.008388607"} 1
inference_latency_seconds_bucket{le="0.009786708"} 1
inference_latency_seconds_bucket{le="0.011184809"} 1
inference_latency_seconds_bucket{le="0.01258291"} 1
inference_latency_seconds_bucket{le="0.013981011"} 2
inference_latency_seconds_bucket{le="0.015379112"} 2
inference_latency_seconds_bucket{le="0.016777216"} 2
inference_latency_seconds_bucket{le="0.022369621"} 2
inference_latency_seconds_bucket{le="0.027962026"} 2
inference_latency_seconds_bucket{le="0.033554431"} 2
inference_latency_seconds_bucket{le="0.039146836"} 2
inference_latency_seconds_bucket{le="0.044739241"} 2
inference_latency_seconds_bucket{le="0.050331646"} 2
inference_latency_seconds_bucket{le="0.055924051"} 2
inference_latency_seconds_bucket{le="0.061516456"} 2
inference_latency_seconds_bucket{le="0.067108864"} 2
inference_latency_seconds_bucket{le="0.089478485"} 2
inference_latency_seconds_bucket{le="0.111848106"} 2
inference_latency_seconds_bucket{le="0.134217727"} 2
inference_latency_seconds_bucket{le="0.156587348"} 2
inference_latency_seconds_bucket{le="0.178956969"} 2
inference_latency_seconds_bucket{le="0.20132659"} 2
inference_latency_seconds_bucket{le="0.223696211"} 2
inference_latency_seconds_bucket{le="0.246065832"} 2
inference_latency_seconds_bucket{le="0.268435456"} 2
inference_latency_seconds_bucket{le="0.357913941"} 2
inference_latency_seconds_bucket{le="0.447392426"} 2
inference_latency_seconds_bucket{le="0.536870911"} 2
inference_latency_seconds_bucket{le="0.626349396"} 2
inference_latency_seconds_bucket{le="0.715827881"} 2
inference_latency_seconds_bucket{le="0.805306366"} 2
inference_latency_seconds_bucket{le="0.894784851"} 2
inference_latency_seconds_bucket{le="0.984263336"} 2
inference_latency_seconds_bucket{le="1.073741824"} 2
inference_latency_seconds_bucket{le="1.431655765"} 2
inference_latency_seconds_bucket{le="1.789569706"} 3
inference_latency_seconds_bucket{le="2.147483647"} 3
inference_latency_seconds_bucket{le="2.505397588"} 3
inference_latency_seconds_bucket{le="2.863311529"} 3
inference_latency_seconds_bucket{le="3.22122547"} 3
inference_latency_seconds_bucket{le="3.579139411"} 3
inference_latency_seconds_bucket{le="3.937053352"} 3
inference_latency_seconds_bucket{le="4.294967296"} 3
inference_latency_seconds_bucket{le="5.726623061"} 3
inference_latency_seconds_bucket{le="7.158278826"} 3
inference_latency_seconds_bucket{le="8.589934591"} 3
inference_latency_seconds_bucket{le="10.021590356"} 3
inference_latency_seconds_bucket{le="11.453246121"} 3
inference_latency_seconds_bucket{le="12.884901886"} 3
inference_latency_seconds_bucket{le="14.316557651"} 3
inference_latency_seconds_bucket{le="15.748213416"} 3
inference_latency_seconds_bucket{le="17.179869184"} 3
inference_latency_seconds_bucket{le="22.906492245"} 3
inference_latency_seconds_bucket{le="28.633115306"} 3
inference_latency_seconds_bucket{le="30.0"} 3
inference_latency_seconds_bucket{le="+Inf"} 3
inference_latency_seconds_count 3
inference_latency_seconds_sum 1.809854
# HELP inference_latency_seconds_max End-to-end request latency
# TYPE inference_latency_seconds_max gauge
inference_latency_seconds_max 1.7887968
# HELP inference_requests_total Total inference requests
# TYPE inference_requests_total counter
inference_requests_total 3.0
# HELP process_uptime_seconds The uptime of the Java virtual machine
# TYPE process_uptime_seconds gauge
process_uptime_seconds 77.414
```

License

MIT

---
