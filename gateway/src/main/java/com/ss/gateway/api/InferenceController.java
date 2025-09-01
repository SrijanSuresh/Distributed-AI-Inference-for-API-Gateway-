package com.ss.gateway.api;

import com.ss.gateway.db.LoggingService;
import com.ss.worker.proto.ClassifyRequest;
import com.ss.worker.proto.ClassifyResponse;
import com.ss.worker.proto.InferenceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class InferenceController {

  // Worker location (override with env var WORKER_ADDR, e.g. "127.0.0.1:9090")
  private final String workerTarget =
      System.getenv().getOrDefault("WORKER_ADDR", "127.0.0.1:9090");

  private final StringRedisTemplate redis;
  private final LoggingService logger;

  public InferenceController(StringRedisTemplate redis, LoggingService logger) {
    this.redis = redis;
    this.logger = logger;
  }

  // HTTP request & response payloads
  public record Req(String model, String text) {}
  public record Resp(String label, double score, long latencyMs, String workerId, boolean cacheHit) {}

  @PostMapping("/classify")
  public Resp classify(@RequestBody Req req) {
    long t0 = System.nanoTime();

    // Cache key = "<model>::<md5(text)>"
    String textHash = DigestUtils.md5DigestAsHex(req.text().getBytes(StandardCharsets.UTF_8));
    String key = req.model() + "::" + textHash;

    // ---- 1) Try Redis (cache hit)
    String cached = redis.opsForValue().get(key);
    if (cached != null) {
      String[] parts = cached.split("\\|", 3); // label|score|workerId
      String label = parts[0];
      double score = Double.parseDouble(parts[1]);
      String workerId = parts[2];
      long ms = (System.nanoTime() - t0) / 1_000_000;
      logger.log(req.model(), textHash, label, score, ms, true, workerId);
      return new Resp(label, score, ms, workerId, true);
    }

    // ---- 2) Cache miss → call worker over gRPC
    String[] hp = workerTarget.split(":");
    ManagedChannel ch = ManagedChannelBuilder
        .forAddress(hp[0], Integer.parseInt(hp[1]))
        .usePlaintext()
        .build();

    try {
      InferenceGrpc.InferenceBlockingStub stub = InferenceGrpc.newBlockingStub(ch);

      ClassifyRequest grpcReq = ClassifyRequest.newBuilder()
          .setModel(req.model())
          .setInputText(req.text())
          .build();

      ClassifyResponse r = stub.classify(grpcReq);

      // Save to Redis with TTL
      String value = r.getLabel() + "|" + r.getScore() + "|" + r.getWorkerId();
      redis.opsForValue().set(key, value, Duration.ofMinutes(30));

      long ms = (System.nanoTime() - t0) / 1_000_000;
      logger.log(req.model(), textHash, r.getLabel(), r.getScore(), ms, false, r.getWorkerId());
      return new Resp(r.getLabel(), r.getScore(), ms, r.getWorkerId(), false);

    } finally {
      ch.shutdownNow();
    }
  }
}
