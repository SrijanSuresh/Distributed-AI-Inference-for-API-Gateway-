package com.ss.gateway.api;

import com.ss.worker.proto.ClassifyRequest;
import com.ss.worker.proto.ClassifyResponse;
import com.ss.worker.proto.InferenceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InferenceController {

  // For now, talk to the worker on localhost:9090.
  // Later we'll make this a list and round-robin it.
  private final String workerTarget = System.getenv().getOrDefault("WORKER_ADDR", "localhost:9090");

  // Request body we accept from the client
  public record Req(String model, String text) {}
  // Response body we return to the client
  public record Resp(String label, double score, long latencyMs, String workerId) {}

  @PostMapping("/classify")
  public Resp classify(@RequestBody Req req) {
    // 1) Open a gRPC channel to the worker
    ManagedChannel ch = ManagedChannelBuilder.forTarget(workerTarget)
        .usePlaintext()   // no TLS for local dev
        .build();

    try {
      // 2) Create a blocking client stub
      InferenceGrpc.InferenceBlockingStub stub = InferenceGrpc.newBlockingStub(ch);

      // 3) Build the gRPC request from the HTTP JSON
      ClassifyRequest grpcReq = ClassifyRequest.newBuilder()
          .setModel(req.model())
          .setInputText(req.text())
          .build();

      // 4) Call the worker and get the response
      ClassifyResponse r = stub.classify(grpcReq);

      // 5) Map to our HTTP JSON response and return
      return new Resp(r.getLabel(), r.getScore(), r.getLatencyMs(), r.getWorkerId());
    } finally {
      ch.shutdownNow(); // close the channel
    }
  }
}
