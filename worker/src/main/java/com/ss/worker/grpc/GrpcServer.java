package com.ss.worker.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class GrpcServer {
  private final InferenceService service;
  private Server server;
  private Thread awaitThread;

  public GrpcServer(InferenceService service) {
    this.service = service;
  }

  @PostConstruct
  public void start() throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
    server = ServerBuilder.forPort(port)
        .addService(service)
        .build()
        .start();

    System.out.println("[worker] gRPC listening on " + port + " (Ctrl+C to stop)");

    // Keep JVM alive: non-daemon thread blocks on server termination
    awaitThread = new Thread(() -> {
      try {
        server.awaitTermination();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "grpc-await");
    awaitThread.setDaemon(false);
    awaitThread.start();
  }

  @PreDestroy
  public void stop() {
    System.out.println("[worker] shutting down gRPC server…");
    if (server != null) server.shutdownNow();
  }
}
