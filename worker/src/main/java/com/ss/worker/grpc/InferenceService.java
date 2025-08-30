package com.ss.worker.grpc;

import com.ss.worker.proto.ClassifyRequest;
import com.ss.worker.proto.ClassifyResponse;
import com.ss.worker.proto.InferenceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InferenceService extends InferenceGrpc.InferenceImplBase {
  private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

  @Override
  public void classify(ClassifyRequest req, StreamObserver<ClassifyResponse> out) {
    long t0 = System.nanoTime();
    String text = req.getInputText().toLowerCase();

    String label =
        (text.contains("love") || text.contains("great") || text.contains("awesome")) ? "POSITIVE" :
        (text.contains("hate") || text.contains("bad")   || text.contains("terrible")) ? "NEGATIVE" :
        "NEUTRAL";
    double score = label.equals("NEUTRAL") ? 0.50 : 0.92;

    long latencyMs = (System.nanoTime() - t0) / 1_000_000;

    ClassifyResponse resp = ClassifyResponse.newBuilder()
        .setLabel(label)
        .setScore(score)
        .setLatencyMs(latencyMs)
        .setWorkerId(workerId)
        .build();

    out.onNext(resp);
    out.onCompleted();
  }
}
