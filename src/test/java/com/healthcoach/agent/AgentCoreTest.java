package com.healthcoach.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
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
import java.util.List;
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

        TestableAgentCore(PromptBuilder pb, JsonObject cfg, PreferencesStore prefStore) {
            super(pb, cfg, prefStore);
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

    // ---------- Gemini native endpoint style ----------

    private JsonObject nativeCfg() {
        JsonObject c = new JsonObject();
        c.addProperty("apiKey", "AIza-fake");
        c.addProperty("baseUrl", "https://generativelanguage.googleapis.com/v1beta");
        c.addProperty("model", "gemma-4-31b-it");
        c.addProperty("maxTokens", 100);
        c.addProperty("temperature", 0.5);
        c.addProperty("endpointStyle", "gemini-native");
        return c;
    }

    @Test
    void t78_nativeBodyHasSystemInstructionAndContents() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg());
        String body = core.buildRequestBody("我中午吃了便當");
        assertTrue(body.contains("systemInstruction"), "should contain systemInstruction: " + body.substring(0, Math.min(200, body.length())));
        assertTrue(body.contains("contents"), "should contain contents array");
        assertTrue(body.contains("generationConfig"), "should contain generationConfig");
        assertTrue(body.contains("maxOutputTokens"), "should use maxOutputTokens not max_tokens");
        assertTrue(body.contains("我中午吃了便當"), "should contain literal user message");
        assertTrue(body.contains("\"parts\""), "Gemini format uses 'parts'");
    }

    @Test
    void t79_nativeResponseParsing() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg());
        core.responses.add(new FakeHttpResponse(200,
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"嗨\"}]}}]}"));
        assertEquals("嗨", core.chat("hi"));
    }

    @Test
    void t80_nativeUsesGoogApiKeyHeader() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg());
        core.responses.add(new FakeHttpResponse(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"));
        core.chat("hi");
        String googKey = core.lastRequest.headers().firstValue("x-goog-api-key").orElse("");
        assertEquals("AIza-fake", googKey);
        String auth = core.lastRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("", auth, "native mode must NOT send Authorization header");
    }

    @Test
    void t81_nativeUrlIncludesModelAndGenerateContent() throws Exception {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg());
        core.responses.add(new FakeHttpResponse(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"));
        core.chat("hi");
        String uri = core.lastRequest.uri().toString();
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemma-4-31b-it:generateContent",
                uri);
    }

    @Test
    void t82_nativeMapsAssistantRoleToModel() {
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg());
        List<ConversationStore.Message> hist = List.of(
                new ConversationStore.Message("user", "earlier"),
                new ConversationStore.Message("assistant", "previous reply")
        );
        String body = core.buildRequestBody("now", hist);
        assertTrue(body.contains("\"role\":\"model\""), "assistant role must be mapped to 'model': " + body);
        assertTrue(body.contains("previous reply"), "history assistant text must appear");
        assertTrue(body.contains("earlier"), "history user text must appear");
        assertTrue(body.contains("\"role\":\"user\""), "user role kept as 'user'");
    }

    // ---------- /effort wiring ----------

    private JsonObject gemini3Cfg() {
        JsonObject c = new JsonObject();
        c.addProperty("apiKey", "AIza-fake");
        c.addProperty("baseUrl", "https://generativelanguage.googleapis.com/v1beta");
        c.addProperty("model", "gemini-3.5-flash");
        c.addProperty("maxTokens", 100);
        c.addProperty("temperature", 0.5);
        c.addProperty("endpointStyle", "gemini-native");
        return c;
    }

    @Test
    void tEffortLowIncludedInGeminiNativeBodyForGemini3() {
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.setEffort("low");
        TestableAgentCore core = new TestableAgentCore(promptBuilder, gemini3Cfg(), prefStore);
        String body = core.buildRequestBody("hi");
        JsonElement root = JsonParser.parseString(body);
        JsonObject genCfg = root.getAsJsonObject().getAsJsonObject("generationConfig");
        assertNotNull(genCfg, "generationConfig missing");
        JsonObject thinking = genCfg.getAsJsonObject("thinkingConfig");
        assertNotNull(thinking, "thinkingConfig should be present for gemini-3* models");
        assertEquals(0, thinking.get("thinkingBudget").getAsInt());
    }

    @Test
    void tEffortHighForGemini3Produces8192Budget() {
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.setEffort("high");
        TestableAgentCore core = new TestableAgentCore(promptBuilder, gemini3Cfg(), prefStore);
        String body = core.buildRequestBody("hi");
        JsonElement root = JsonParser.parseString(body);
        JsonObject genCfg = root.getAsJsonObject().getAsJsonObject("generationConfig");
        JsonObject thinking = genCfg.getAsJsonObject("thinkingConfig");
        assertNotNull(thinking, "thinkingConfig should be present");
        assertEquals(8192, thinking.get("thinkingBudget").getAsInt());
    }

    @Test
    void tEffortSkippedForGemma() {
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.setEffort("high");
        // nativeCfg() uses model gemma-4-31b-it
        TestableAgentCore core = new TestableAgentCore(promptBuilder, nativeCfg(), prefStore);
        String body = core.buildRequestBody("hi");
        assertTrue(!body.contains("thinkingConfig"),
                "thinkingConfig must NOT be present for Gemma models, body=" + body);
    }

    @Test
    void tEffortIncludedInOpenAiBody() {
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.setEffort("high");
        TestableAgentCore core = new TestableAgentCore(promptBuilder, cfg, prefStore);
        String body = core.buildRequestBody("hi");
        JsonElement root = JsonParser.parseString(body);
        JsonObject obj = root.getAsJsonObject();
        assertTrue(obj.has("reasoning_effort"), "openai body should contain reasoning_effort: " + body);
        assertEquals("high", obj.get("reasoning_effort").getAsString());
    }

    // ---------- Three-tier effort resolution (preferences → config → "medium") ----------

    /** Write preferences.json with an explicitly-blank effort field, bypassing
     *  PreferencesStore.setEffort which would normalize blank → "medium". */
    private void writePreferencesWithBlankEffort() throws IOException {
        String json = "{\n"
                + "  \"timezone\": \"Asia/Taipei\",\n"
                + "  \"mealReminders\": [],\n"
                + "  \"workoutReminder\": \"\",\n"
                + "  \"weeklySummary\": \"\",\n"
                + "  \"effort\": \"\"\n"
                + "}\n";
        Files.writeString(tempDir.resolve("preferences.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void tConfigEffortUsedWhenNoPreference() throws Exception {
        JsonObject c = gemini3Cfg();
        c.addProperty("effort", "high");  // config default = high
        writePreferencesWithBlankEffort();  // preferences = blank → should fall through
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        TestableAgentCore core = new TestableAgentCore(promptBuilder, c, prefStore);
        String body = core.buildRequestBody("hi");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        int budget = parsed.getAsJsonObject("generationConfig")
                .getAsJsonObject("thinkingConfig")
                .get("thinkingBudget").getAsInt();
        assertEquals(8192, budget,
                "config effort=high should produce thinkingBudget=8192 when preferences blank");
    }

    @Test
    void tPreferenceOverridesConfigEffort() {
        JsonObject c = gemini3Cfg();
        c.addProperty("effort", "high");  // config says high
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.setEffort("low");  // preferences says low → should win
        TestableAgentCore core = new TestableAgentCore(promptBuilder, c, prefStore);
        String body = core.buildRequestBody("hi");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        int budget = parsed.getAsJsonObject("generationConfig")
                .getAsJsonObject("thinkingConfig")
                .get("thinkingBudget").getAsInt();
        assertEquals(0, budget, "preferences=low should beat config=high");
    }

    @Test
    void tMissingConfigEffortFallsBackToMedium() throws Exception {
        JsonObject c = gemini3Cfg();
        // do NOT add effort to cfg → simulate old config.json without effort field
        writePreferencesWithBlankEffort();  // preferences blank too
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        TestableAgentCore core = new TestableAgentCore(promptBuilder, c, prefStore);
        String body = core.buildRequestBody("hi");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        int budget = parsed.getAsJsonObject("generationConfig")
                .getAsJsonObject("thinkingConfig")
                .get("thinkingBudget").getAsInt();
        assertEquals(1024, budget, "default fallback should be medium → 1024");
    }
}
