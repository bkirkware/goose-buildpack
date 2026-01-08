package org.tanzu.goose.cf.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tanzu.goose.cf.GooseExecutionException;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * REST controller for Goose CLI operations.
 * <p>
 * This controller provides HTTP endpoints for executing Goose commands:
 * </p>
 * <ul>
 *   <li>POST /api/goose/execute - Execute a prompt and get the complete response</li>
 *   <li>GET /api/goose/stream - Execute a prompt with streaming response (SSE)</li>
 *   <li>GET /api/goose/health - Check if Goose CLI is available</li>
 *   <li>GET /api/goose/version - Get Goose CLI version</li>
 * </ul>
 *
 * <h2>Enable/Disable</h2>
 * <p>
 * This controller is automatically enabled when:
 * </p>
 * <ul>
 *   <li>Spring WebFlux is on the classpath</li>
 *   <li>Property {@code goose.controller.enabled} is {@code true} (default)</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/goose")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.server.RouterFunction")
@ConditionalOnProperty(name = "goose.controller.enabled", havingValue = "true", matchIfMissing = true)
public class GooseController {

    private final GooseExecutor gooseExecutor;

    public GooseController(GooseExecutor gooseExecutor) {
        this.gooseExecutor = gooseExecutor;
    }

    /**
     * Execute a Goose prompt and return the complete response.
     *
     * @param request the request containing the prompt
     * @return the Goose response
     */
    @PostMapping("/execute")
    public ResponseEntity<GooseResponse> execute(@RequestBody GooseRequest request) {
        try {
            GooseOptions options = buildOptions(request);
            String result = gooseExecutor.execute(request.prompt(), options);
            return ResponseEntity.ok(new GooseResponse(result, null));
        } catch (GooseExecutionException e) {
            return ResponseEntity.status(500)
                    .body(new GooseResponse(null, "Error: " + e.getMessage()));
        }
    }

    /**
     * Execute a Goose prompt with streaming response (Server-Sent Events).
     *
     * @param prompt the prompt to execute
     * @return a Flux of response lines
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String prompt) {
        return Flux.create(sink -> {
            try {
                gooseExecutor.executeStreaming(prompt)
                        .forEach(sink::next);
                sink.complete();
            } catch (GooseExecutionException e) {
                sink.error(e);
            }
        });
    }

    /**
     * Check if Goose CLI is available.
     *
     * @return health status
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean available = gooseExecutor.isAvailable();
        String version = available ? gooseExecutor.getVersion() : null;
        return ResponseEntity.ok(new HealthResponse(available, version));
    }

    /**
     * Get Goose CLI version.
     *
     * @return version information
     */
    @GetMapping("/version")
    public ResponseEntity<VersionResponse> version() {
        String version = gooseExecutor.getVersion();
        if (version != null) {
            return ResponseEntity.ok(new VersionResponse(version));
        }
        return ResponseEntity.status(503)
                .body(new VersionResponse(null));
    }

    private GooseOptions buildOptions(GooseRequest request) {
        GooseOptions.Builder builder = GooseOptions.builder();

        if (request.timeout() != null) {
            builder.timeout(Duration.ofMinutes(request.timeout()));
        }
        if (request.maxTurns() != null) {
            builder.maxTurns(request.maxTurns());
        }
        if (request.provider() != null) {
            builder.provider(request.provider());
        }
        if (request.model() != null) {
            builder.model(request.model());
        }

        return builder.build();
    }

    /**
     * Request payload for Goose execution.
     */
    public record GooseRequest(
            String prompt,
            Integer timeout,
            Integer maxTurns,
            String provider,
            String model
    ) {}

    /**
     * Response payload for Goose execution.
     */
    public record GooseResponse(
            String result,
            String error
    ) {}

    /**
     * Health check response.
     */
    public record HealthResponse(
            boolean available,
            String version
    ) {}

    /**
     * Version response.
     */
    public record VersionResponse(
            String version
    ) {}
}

