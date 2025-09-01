package com.ss.gateway.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoggingService {
  private final JdbcTemplate jdbc;

  public LoggingService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void log(String model, String textHash, String label, double score,
                  long latencyMs, boolean cacheHit, String workerId) {
    jdbc.update(
      "INSERT INTO inference_log (model, text_hash, label, score, latency_ms, cache_hit, worker_id) " +
      "VALUES (?,?,?,?,?,?,?)",
      model, textHash, label, score, latencyMs, cacheHit, workerId
    );
  }
}
