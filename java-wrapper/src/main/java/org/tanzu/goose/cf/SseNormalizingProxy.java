package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP proxy that normalizes Server-Sent Events (SSE) format.
 * <p>
 * This proxy solves the incompatibility between GenAI proxy services (which return
 * {@code data:{...}} without a space) and Goose CLI (which expects {@code data: {...}}
 * with a space after the colon).
 * </p>
 * <p>
 * The proxy runs on a local port and:
 * </p>
 * <ul>
 *   <li>Forwards HTTP requests to the upstream GenAI service</li>
 *   <li>Transforms SSE responses: {@code data:} → {@code data: } (adds space)</li>
 *   <li>Passes through all headers including Authorization</li>
 * </ul>
 * <p>
 * <b>Usage:</b>
 * </p>
 * <pre>{@code
 * SseNormalizingProxy proxy = new SseNormalizingProxy(
 *     "https://genai.example.com/openai",
 *     "Bearer my-api-key"
 * );
 * proxy.start();
 * 
 * // Configure Goose to use proxy
 * String proxyUrl = proxy.getProxyUrl();  // e.g., "http://localhost:54321"
 * 
 * // When done
 * proxy.stop();
 * }</pre>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class SseNormalizingProxy implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SseNormalizingProxy.class);
    private static final AtomicInteger PROXY_COUNTER = new AtomicInteger(0);

    private final String upstreamBaseUrl;
    private final String authorizationHeader;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int localPort;

    /**
     * Creates a new SSE normalizing proxy.
     *
     * @param upstreamBaseUrl the upstream GenAI service URL (e.g., "https://genai.example.com/openai")
     * @param apiKey the API key for authentication (will be sent as Bearer token)
     */
    public SseNormalizingProxy(String upstreamBaseUrl, String apiKey) {
        this.upstreamBaseUrl = upstreamBaseUrl.endsWith("/") 
            ? upstreamBaseUrl.substring(0, upstreamBaseUrl.length() - 1) 
            : upstreamBaseUrl;
        this.authorizationHeader = apiKey != null ? "Bearer " + apiKey : null;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // Force HTTP/1.1 to avoid HTTP/2 RST_STREAM issues
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Starts the proxy server on an available local port.
     *
     * @throws IOException if the server socket cannot be created
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            logger.debug("Proxy already running on port {}", localPort);
            return;
        }

        // Find an available port
        serverSocket = new ServerSocket(0);
        localPort = serverSocket.getLocalPort();
        running.set(true);

        int proxyId = PROXY_COUNTER.incrementAndGet();
        logger.info("Starting SSE normalizing proxy #{} on port {} -> {}", proxyId, localPort, upstreamBaseUrl);

        acceptThread = Thread.ofVirtual().name("sse-proxy-accept-" + proxyId).start(() -> {
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.error("Error accepting connection", e);
                    }
                }
            }
        });
    }

    /**
     * Stops the proxy server and releases resources.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        logger.info("Stopping SSE normalizing proxy on port {}", localPort);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing server socket", e);
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the local proxy URL that Goose should use.
     *
     * @return the proxy URL (e.g., "http://localhost:54321")
     */
    public String getProxyUrl() {
        return "http://localhost:" + localPort;
    }

    /**
     * Returns the local port the proxy is listening on.
     *
     * @return the local port number
     */
    public int getLocalPort() {
        return localPort;
    }

    /**
     * Returns whether the proxy is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    private void handleConnection(Socket clientSocket) {
        try (clientSocket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = clientSocket.getOutputStream()) {

            // Read HTTP request
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            logger.info("Proxy received request: {}", requestLine);

            // Parse request line: "POST /v1/chat/completions HTTP/1.1"
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                sendError(out, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            String path = parts[1];

            // Read headers
            StringBuilder headersBuilder = new StringBuilder();
            String contentType = null;
            int contentLength = -1;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-type:")) {
                    contentType = line.substring("content-type:".length()).trim();
                } else if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
                headersBuilder.append(line).append("\r\n");
            }

            // Read body if present
            String body = null;
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int read = reader.read(bodyChars, 0, contentLength);
                if (read > 0) {
                    body = new String(bodyChars, 0, read);
                }
            }

            // Forward request to upstream
            String upstreamUrl = upstreamBaseUrl + path;
            logger.info("Forwarding to upstream: {} {} (body length: {})", method, upstreamUrl, 
                body != null ? body.length() : 0);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .timeout(Duration.ofMinutes(10));

            if (authorizationHeader != null) {
                requestBuilder.header("Authorization", authorizationHeader);
                logger.debug("Added Authorization header (length: {})", authorizationHeader.length());
            }
            if (contentType != null) {
                requestBuilder.header("Content-Type", contentType);
            }

            if ("POST".equalsIgnoreCase(method) && body != null) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else if ("GET".equalsIgnoreCase(method)) {
                requestBuilder.GET();
            } else {
                requestBuilder.method(method, body != null 
                    ? HttpRequest.BodyPublishers.ofString(body) 
                    : HttpRequest.BodyPublishers.noBody());
            }

            // Check if this is a streaming request
            boolean isStreaming = body != null && body.contains("\"stream\":true");
            logger.info("Request isStreaming: {}", isStreaming);

            if (isStreaming) {
                handleStreamingResponse(requestBuilder.build(), out);
            } else {
                handleNonStreamingResponse(requestBuilder.build(), out);
            }

        } catch (java.net.SocketException e) {
            // Client disconnected - this is normal when Goose CLI exits
            logger.debug("Client disconnected: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling proxy connection", e);
        }
    }

    private void handleStreamingResponse(HttpRequest request, OutputStream out) throws IOException, InterruptedException {
        logger.info("Sending streaming request to: {}", request.uri());
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        logger.info("Upstream response status: {}", response.statusCode());
        
        // Log response headers for debugging
        response.headers().map().forEach((key, values) -> 
            logger.debug("Upstream header: {} = {}", key, values));
        
        // Write response headers - must match what the client expects for SSE
        writeResponseHeaders(out, response.statusCode(), "text/event-stream; charset=utf-8");
        out.flush();

        // Stream and normalize SSE data using chunked transfer encoding
        int lineCount = 0;
        boolean hasToolCalls = false;
        try (InputStream in = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                String normalizedLine = normalizeSseLine(line);
                
                // Check if this line contains tool_calls - important for debugging
                if (line.contains("tool_calls") || line.contains("\"function\"")) {
                    hasToolCalls = true;
                    // Log full content for tool call lines (critical for debugging)
                    logger.info("SSE line {} (TOOL CALL): {}", lineCount, line);
                } else if (lineCount <= 10 || line.contains("finish_reason")) {
                    // Log first few lines and finish reason for debugging
                    logger.info("SSE line {}: {} -> {}", lineCount, 
                        line.length() > 80 ? line.substring(0, 80) + "..." : line,
                        normalizedLine.length() > 80 ? normalizedLine.substring(0, 80) + "..." : normalizedLine);
                }
                
                // Write as a proper HTTP chunk: size in hex + CRLF + data + CRLF
                byte[] data = (normalizedLine + "\n").getBytes(StandardCharsets.UTF_8);
                writeChunk(out, data);
            }
        } catch (IOException e) {
            // Handle upstream connection closing prematurely
            // Some GenAI proxies don't properly terminate their chunked encoding streams
            // They may close the connection without sending the final "0\r\n\r\n" termination
            if (e.getMessage() != null && (
                    e.getMessage().contains("chunked transfer encoding") ||
                    e.getMessage().contains("EOF") ||
                    e.getMessage().contains("closed"))) {
                logger.warn("Upstream closed connection prematurely after {} lines (GenAI proxy chunked encoding issue)", lineCount);
            } else {
                throw e;
            }
        } finally {
            // Always write the final zero-length chunk to signal end of stream to client
            try {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                logger.debug("Client already disconnected when sending final chunk");
            }
            logger.info("Streaming complete, sent {} lines, hasToolCalls={}", lineCount, hasToolCalls);
        }
    }
    
    /**
     * Writes a single chunk in HTTP chunked transfer encoding format.
     */
    private void writeChunk(OutputStream out, byte[] data) throws IOException {
        // Format: SIZE_IN_HEX\r\n DATA \r\n
        String sizeHex = Integer.toHexString(data.length);
        out.write((sizeHex + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleNonStreamingResponse(HttpRequest request, OutputStream out) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
        writeResponseHeaders(out, response.statusCode(), contentType, response.body().length());
        out.write(response.body().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeResponseHeaders(OutputStream out, int statusCode, String contentType) throws IOException {
        // Use HTTP/1.1 with chunked transfer encoding for streaming responses
        // reqwest (used by Goose) handles chunked encoding properly
        String headers = "HTTP/1.1 " + statusCode + " " + getStatusText(statusCode) + "\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        logger.debug("Wrote streaming response headers");
    }

    private void writeResponseHeaders(OutputStream out, int statusCode, String contentType, int contentLength) throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + getStatusText(statusCode) + "\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(OutputStream out, int statusCode, String message) throws IOException {
        String body = "{\"error\":\"" + message + "\"}";
        writeResponseHeaders(out, statusCode, "application/json", body.length());
        out.write(body.getBytes(StandardCharsets.UTF_8));
    }

    private String getStatusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    /**
     * Normalizes an SSE line by ensuring proper spacing after "data:" and adding
     * required fields for OpenAI compatibility.
     * <p>
     * Transforms:
     * <ul>
     *   <li>{@code data:{...}} → {@code data: {...}} (adds space after colon)</li>
     *   <li>{@code data:[DONE]} → {@code data: [DONE]} (adds space after colon)</li>
     *   <li>Adds {@code "index":0} to tool_calls that are missing it (required by Goose)</li>
     * </ul>
     * Lines that already have proper spacing or aren't data lines are passed through unchanged.
     * </p>
     *
     * @param line the SSE line to normalize
     * @return the normalized line
     */
    static String normalizeSseLine(String line) {
        if (line == null) {
            return null;
        }

        String result = line;
        
        // Check if line starts with "data:" but NOT "data: " (with space)
        if (result.startsWith("data:") && !result.startsWith("data: ")) {
            // Insert space after "data:"
            result = "data: " + result.substring(5);
        }
        
        // Add index field to tool_calls if missing
        // GenAI returns: "tool_calls":[{"id":"xxx","type":"function",...}]
        // OpenAI expects: "tool_calls":[{"index":0,"id":"xxx","type":"function",...}]
        result = addToolCallIndex(result);
        
        if (!result.equals(line)) {
            logger.trace("Normalized SSE line: {} -> {}", 
                line.length() > 50 ? line.substring(0, 50) + "..." : line,
                result.length() > 50 ? result.substring(0, 50) + "..." : result);
        }

        return result;
    }
    
    /**
     * Adds "index":0 to tool_calls entries that are missing the index field.
     * OpenAI's streaming format requires an index on each tool_call for proper
     * aggregation across chunks.
     */
    static String addToolCallIndex(String line) {
        if (!line.contains("tool_calls")) {
            return line;
        }
        
        // Pattern: "tool_calls":[{"id": -> "tool_calls":[{"index":0,"id":
        // We need to add "index":N before each tool call object that has "id" but no "index"
        // Simple approach: replace tool_calls arrays to add index
        
        // Match tool_call objects that start with {"id" (missing index)
        // and replace with {"index":0,"id"
        String result = line;
        
        // Handle each tool call in the array - they should each get sequential indices
        // For simplicity, since most responses have one tool call, use index 0
        // Pattern: "tool_calls":[{"id" -> "tool_calls":[{"index":0,"id"
        if (result.contains("\"tool_calls\":[{\"id\"")) {
            result = result.replace("\"tool_calls\":[{\"id\"", "\"tool_calls\":[{\"index\":0,\"id\"");
            logger.debug("Added index:0 to tool_calls");
        }
        
        // Also handle case where id is not the first field but index is still missing
        // Pattern: "tool_calls":[{"type" -> "tool_calls":[{"index":0,"type"
        if (result.contains("\"tool_calls\":[{\"type\"") && !result.contains("\"index\":")) {
            result = result.replace("\"tool_calls\":[{\"type\"", "\"tool_calls\":[{\"index\":0,\"type\"");
            logger.debug("Added index:0 to tool_calls (type-first variant)");
        }
        
        return result;
    }
}
