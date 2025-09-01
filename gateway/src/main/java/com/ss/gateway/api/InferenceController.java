package com.ss.gateway.api;

import com.ss.worker.proto.ClassifyRequest;
import com.ss.worker.proto.ClassifyResponse;
import com.ss.worker.proto.InferenceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.charset.StandardCharsets;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class InferenceController {

  // where the worker lives
  private final String workerTarget = System.getenv().getOrDefault("WORKER_ADDR", "127.0.0.1:9090");
  // Redis client Spring gives us
  private final StringRedisTemplate redis;

  // Spring injects redis for us
  public InferenceController(StringRedisTemplate redis) {
    this.redis = redis;
  }

  // incoming JSON body
  public record Req(String model, String text) {}
  // outgoing JSON body (now includes cacheHit flag)
  public record Resp(String label, double score, long latencyMs, String workerId, boolean cacheHit) {}

  @PostMapping("/classify")
  public Resp classify(@RequestBody Req req) {
    long t0 = System.nanoTime();

    // make a stable key: "model::sha1(text)"
    String key = req.model() + "::"
        + org.springframework.util.DigestUtils.md5DigestAsHex(req.text().getBytes(StandardCharsets.UTF_8));

    // 1) try Redis first
    String cached = redis.opsForValue().get(key);
    if (cached != null) {
      String[] p = cached.split("\\|", 3); // label|score|workerId
      long ms = (System.nanoTime() - t0) / 1_000_000;
      return new Resp(p[0], Double.parseDouble(p[1]), ms, p[2], true);
    }

    // 2) cache miss -> call worker via gRPC
    String[] hp = workerTarget.split(":");
    ManagedChannel ch = ManagedChannelBuilder.forAddress(hp[0], Integer.parseInt(hp[1]))
        .usePlaintext()
        .build();
    try {
      InferenceGrpc.InferenceBlockingStub stub = InferenceGrpc.newBlockingStub(ch);
      ClassifyRequest grpcReq = ClassifyRequest.newBuilder()
          .setModel(req.model())
          .setInputText(req.text())
          .build();
      ClassifyResponse r = stub.classify(grpcReq);

      // 3) store in Redis with 30-min TTL
      String value = r.getLabel() + "|" + r.getScore() + "|" + r.getWorkerId();
      redis.opsForValue().set(key, value, Duration.ofMinutes(30));

      long ms = (System.nanoTime() - t0) / 1_000_000;
      return new Resp(r.getLabel(), r.getScore(), ms, r.getWorkerId(), false);
    } finally {
      ch.shutdownNow();
    }
  }
}
