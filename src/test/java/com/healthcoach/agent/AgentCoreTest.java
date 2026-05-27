package com.healthcoach.agent;

import com.google.gson.JsonObject;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for AgentCore — verifies request shape, response parsing, retry, and error handling. */
class AgentCoreTest {

    private static final String EMPTY_PROFILE_JSON = ""
            + "{\n"
            + "  \"name\": \"\",\n"
            + "  \"heightCm\": 0.0,\n"
            + "  \"weightKg\": 0.0,\n"
            + "  \"age\": 0,\n"
            + "  \"gender\": \"\",\n"
            + "  \"activityLevel\": \"\",\n"
            + "  \"goal\": \"\",\n"
            + "  \"bmr\": 0,\n"
            + "  \"tdee\": 0,\n"
            + "  \"targetCalories\": 0,\n"
            + "  \"targetProteinG\": 0,\n"
            + "  \"targetCarbsG\": 0,\n"
            + "  \"targetFatG\": 0,\n"
            + "  \"dietaryRestrictions\": [],\n"
            + "  \"notes\": \"\",\n"
            + "  \"updatedAt\": \"\"\n"
            + "}\n";

    private static final String EMPTY_MEMORY_JSON = ""
            + "{\n"
            + "  \"entries\": [],\n"
            + "  \"maxEntries\": 20,\n"
            + "  \"maxChars\": 2200\n"
            + "}\n";

    private static final String NUTRITION_SKILL = ""
            + "---\n"
            + "name: nutrition-advice\n"
            + "description: 飲食建議\n"
            + "---\n\n"
            + "# 飲食建議\n";

    @TempDir
    Path tempDir;

    private PromptBuilder promptBuilder;
    private JsonObject cfg;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("user_profile.json"), EMPTY_PROFILE_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("memory.json"), EMPTY_MEMORY_JSON, StandardCharsets.UTF_8);

        Path nutritionDir = tempDir.resolve("skills").resolve("nutrition-advice");
        Files.createDirectories(nutritionDir);
        Files.writeString(nutritionDir.resolve("SKILL.md"), NUTRITION_SKILL, StandardCharsets.UTF_8);

        Files.createDirectories(tempDir.resolve("logs"));

        MemoryStore ms = new MemoryStore(tempDir);
        SkillManager sm = new SkillManager(tempDir);
        DailyLogStore ds = new DailyLogStore(tempDir);
        promptBuilder = new PromptBuilder(ms, sm, ds);

        cfg = new JsonObject();
        cfg.addProperty("apiKey", "fake-key");
        cfg.addProperty("baseUrl", "https://example.invalid/chat");
        cfg.addProperty("model", "test-model");
        cfg.addProperty("maxTokens", 100);
        cfg.addProperty("temperature", 0.5);
    }

    /** A fake HttpResponse with a fixed status and body, used to mock network calls. */
    static class FakeHttpResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        FakeHttpResponse(int status, String body) { this.status = status; this.body = body; }
        public int statusCode() { return status; }
        public String body() { return body; }
        public HttpRequest request() { return null; }
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        public URI uri() { return null; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    /** Test subclass of AgentCore — replaces sendHttp with a canned-response queue and captures the last request. */
    static class TestableAgentCore extends AgentCore {
        final Deque<FakeHttpResponse> responses = new ArrayDeque<>();
        HttpRequest lastRequest;
        int sendCount = 0;

        TestableAgentCore(PromptBuilder pb, JsonObject cfg) {
            super(pb, cfg);
        }

        @Override
        HttpResponse<String> sendHttp(HttpRequest request) {
            this.lastRequest = request;
            this.sendCount++;
            FakeHttpResponse r = responses.pollFirst();
            if (r == null) {
                throw new IllegalStateException("No more canned responses queued");
            }
            return r;
        }
    }

    @Test
    void t71_chatReturnsContent() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        core.responses.add(new FakeHttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}"));
        assertEquals("Hello", core.chat("hi"));
    }

    @Test
    void t72_requestBodyIncludesSystemPromptAndUserMessage() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        String body = core.buildRequestBody("hi");
        assertNotNull(body);
        assertTrue(body.contains("Coach"), "body should contain Coach from soul.md: " + body.substring(0, Math.min(200, body.length())));
        assertTrue(body.contains("\"hi\""), "body should contain user message 'hi'");
        assertTrue(body.contains("test-model"), "body should contain configured model");
    }

    @Test
    void t73_retriesOn429() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        core.responses.add(new FakeHttpResponse(429, "{\"error\":\"rate limit\"}"));
        core.responses.add(new FakeHttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"after-retry\"}}]}"));
        String result = core.chat("x");
        assertEquals("after-retry", result);
        assertEquals(2, core.sendCount);
    }

    @Test
    void t74_throwsOn500() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        core.responses.add(new FakeHttpResponse(500, "internal error"));
        assertThrows(IOException.class, () -> core.chat("x"));
    }

    @Test
    void t75_throwsOnInvalidJson() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        core.responses.add(new FakeHttpResponse(200, "not-json"));
        assertThrows(IOException.class, () -> core.chat("x"));
    }

    @Test
    void t76_requestBodyContainsLiteralUserMessage() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        String body = core.buildRequestBody("hello world");
        assertTrue(body.contains("hello world"), "body should contain literal user message: " + body);
    }

    @Test
    void t77_authorizationHeaderIsSet() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg);
        core.responses.add(new FakeHttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        core.chat("hi");
        assertNotNull(core.lastRequest, "lastRequest should be captured");
        String auth = core.lastRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("Bearer fake-key", auth);
        String contentType = core.lastRequest.headers().firstValue("Content-Type").orElse("");
        assertEquals("application/json", contentType);
    }
}
