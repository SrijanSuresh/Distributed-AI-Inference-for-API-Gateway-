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

License

MIT