package com.ss.worker.grpc;

import com.ss.worker.proto.ClassifyRequest;
import com.ss.worker.proto.ClassifyResponse;
import com.ss.worker.proto.InferenceGrpc;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class InferenceService extends InferenceGrpc.InferenceImplBase {

  private static final String WORKER_ID = "worker-" + Integer.toHexString((int) System.nanoTime());
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  @Override
  public void classify(ClassifyRequest request, StreamObserver<ClassifyResponse> responseObserver) {
    String model = request.getModel();
    String text = request.getInputText();

    String label;
    double score;

    if ("sentiment".equalsIgnoreCase(model)) {
      // Try real model first; fallback to dummy if anything goes wrong
      try {
        LabelScore ls = callHuggingFaceSentiment(text);
        label = ls.label;
        score = ls.score;
      } catch (Exception e) {
        // fallback (simple heuristic)
        label = text.toLowerCase().contains("love") ? "POSITIVE" : "NEGATIVE";
        score = label.equals("POSITIVE") ? 0.92 : 0.88;
      }
    } else {
      // keep existing dummy behavior for other models
      label = "POSITIVE";
      score  = 0.90;
    }

    ClassifyResponse resp = ClassifyResponse.newBuilder()
        .setLabel(label)
        .setScore(score)
        .setWorkerId(WORKER_ID)
        .build();

    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  // ---------- helpers ----------

  private record LabelScore(String label, double score) {}

  private static LabelScore callHuggingFaceSentiment(String text) throws Exception {
    String token = System.getenv("HF_TOKEN"); // set this in your shell
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("HF_TOKEN not set");
    }

    // Use a popular sentiment model (binary: POSITIVE/NEGATIVE)
    String modelUrl = "https://huggingface.co/distilbert/distilbert-base-uncased-finetuned-sst-2-english";

    // minimal JSON body: {"inputs": "..."}
    String body = "{\"inputs\":" + MAPPER.writeValueAsString(text) + "}";

    HttpRequest req = HttpRequest.newBuilder(URI.create(modelUrl))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() >= 400) {
      throw new RuntimeException("HF API error: " + res.statusCode() + " " + res.body());
    }

    // Response can be either:
    // [ [{"label":"POSITIVE","score":0.99}, {...}] ]
    // or (warmup): {"estimated_time":...}
    JsonNode root = MAPPER.readTree(res.body());
    // Handle warmup delay (model cold start)
    if (root.isObject() && root.has("estimated_time")) {
      // quick retry once after a short sleep
      Thread.sleep(800L);
      res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      root = MAPPER.readTree(res.body());
    }

    // Normalize both shapes
    JsonNode arr = root;
    if (root.isArray() && root.size() > 0 && root.get(0).isArray()) {
      arr = root.get(0); // unwrap extra level
    }

    String bestLabel = "NEUTRAL";
    double bestScore = 0.0;

    if (arr.isArray()) {
      for (JsonNode n : arr) {
        String lbl = n.path("label").asText("NEUTRAL");
        double sc  = n.path("score").asDouble(0.0);
        if (sc > bestScore) {
          bestScore = sc;
          bestLabel = lbl;
        }
      }
    }

    if (bestScore == 0.0) throw new RuntimeException("Empty HF response: " + res.body());
    return new LabelScore(bestLabel, bestScore);
  }
}
