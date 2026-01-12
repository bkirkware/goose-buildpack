package org.tanzu.goose.cf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SseNormalizingProxy}.
 */
class SseNormalizingProxyTest {

    @ParameterizedTest(name = "normalize: \"{0}\" -> \"{1}\"")
    @CsvSource({
        // GenAI proxy format (no space) -> normalized (with space)
        "'data:{\"id\":\"123\"}', 'data: {\"id\":\"123\"}'",
        "'data:[DONE]', 'data: [DONE]'",
        "'data:test', 'data: test'",
        
        // Already correct format (with space) -> unchanged
        "'data: {\"id\":\"123\"}', 'data: {\"id\":\"123\"}'",
        "'data: [DONE]', 'data: [DONE]'",
        "'data: test', 'data: test'",
        
        // Non-data lines -> unchanged
        "':comment line', ':comment line'",
        "'event: message', 'event: message'",
        "'', ''",
        "'id: 12345', 'id: 12345'",
        "'retry: 1000', 'retry: 1000'"
    })
    void testNormalizeSseLine(String input, String expected) {
        // Remove surrounding quotes from CSV (they're needed for CSV parsing)
        String cleanInput = input.replace("'", "");
        String cleanExpected = expected.replace("'", "");
        
        String result = SseNormalizingProxy.normalizeSseLine(cleanInput);
        assertEquals(cleanExpected, result);
    }

    @Test
    void testNormalizeSseLineNull() {
        assertNull(SseNormalizingProxy.normalizeSseLine(null));
    }

    @Test
    void testNormalizeSseLineWithComplexJson() {
        String input = "data:{\"id\":\"chatcmpl-123\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}";
        String expected = "data: {\"id\":\"chatcmpl-123\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}";
        
        assertEquals(expected, SseNormalizingProxy.normalizeSseLine(input));
    }

    @Test
    void testProxyStartStop() throws Exception {
        // Create proxy with dummy upstream
        SseNormalizingProxy proxy = new SseNormalizingProxy("http://localhost:9999", "test-key");
        
        assertFalse(proxy.isRunning());
        
        proxy.start();
        assertTrue(proxy.isRunning());
        assertTrue(proxy.getLocalPort() > 0);
        assertTrue(proxy.getProxyUrl().startsWith("http://localhost:"));
        
        proxy.stop();
        assertFalse(proxy.isRunning());
    }

    @Test
    void testProxyAutoCloseable() throws Exception {
        SseNormalizingProxy proxy = new SseNormalizingProxy("http://localhost:9999", "test-key");
        proxy.start();
        assertTrue(proxy.isRunning());
        
        proxy.close();
        assertFalse(proxy.isRunning());
    }

    @Test
    void testProxyStartIdempotent() throws Exception {
        SseNormalizingProxy proxy = new SseNormalizingProxy("http://localhost:9999", "test-key");
        
        proxy.start();
        int port1 = proxy.getLocalPort();
        
        // Second start should be idempotent
        proxy.start();
        int port2 = proxy.getLocalPort();
        
        assertEquals(port1, port2, "Port should remain the same after second start");
        assertTrue(proxy.isRunning());
        
        proxy.stop();
    }
    
    // Tests for tool call index normalization
    
    @Test
    void testAddToolCallIndexWhenMissing() {
        // GenAI format: tool_calls without index
        String input = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-123\",\"type\":\"function\",\"function\":{\"name\":\"test\"}}]}}]}";
        String result = SseNormalizingProxy.normalizeSseLine(input);
        
        assertTrue(result.contains("\"index\":0"), "Should add index:0 to tool_calls");
        assertTrue(result.contains("\"index\":0,\"id\":\"call-123\""), "Index should be before id");
    }
    
    @Test
    void testAddToolCallIndexWithTypeFirst() {
        // Some formats have type before id
        String input = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"type\":\"function\",\"id\":\"call-123\"}]}}]}";
        String result = SseNormalizingProxy.normalizeSseLine(input);
        
        assertTrue(result.contains("\"index\":0"), "Should add index:0 to tool_calls");
    }
    
    @Test
    void testNoDoubleIndex() {
        // OpenAI format: tool_calls already has index
        String input = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-123\",\"type\":\"function\"}]}}]}";
        String result = SseNormalizingProxy.normalizeSseLine(input);
        
        // Should not add another index
        int indexCount = countOccurrences(result, "\"index\":");
        assertEquals(1, indexCount, "Should not duplicate index field");
    }
    
    @Test
    void testNonToolCallLineUnchanged() {
        String input = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello world\"}}]}";
        String result = SseNormalizingProxy.normalizeSseLine(input);
        
        assertEquals(input, result, "Lines without tool_calls should be unchanged");
    }
    
    @Test
    void testCombinedNormalization() {
        // GenAI format: no space AND missing tool call index
        String input = "data:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-123\",\"type\":\"function\"}]}}]}";
        String result = SseNormalizingProxy.normalizeSseLine(input);
        
        assertTrue(result.startsWith("data: "), "Should add space after data:");
        assertTrue(result.contains("\"index\":0"), "Should add index:0 to tool_calls");
    }
    
    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
