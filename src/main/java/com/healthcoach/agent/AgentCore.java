package com.healthcoach.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.healthcoach.memory.PreferencesStore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Sends chat requests to an LLM endpoint, returning the raw assistant content.
 *
 * Supports two endpoint styles via the "endpointStyle" config field:
 *   - "openai"        : OpenAI Chat Completions format (default). Used by OpenRouter
 *                       and Google's OpenAI-compat endpoint (Gemini models only).
 *   - "gemini-native" : Google's generateContent format. Required for Gemma models
 *                       served through Google AI Studio.
 */
public class AgentCore {

    static final String STYLE_OPENAI = "openai";
    static final String STYLE_GEMINI_NATIVE = "gemini-native";

    private final PromptBuilder promptBuilder;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final String endpointStyle;
    private final String configEffort;
    private final PreferencesStore preferencesStore;  // nullable
    private final Gson gson = new Gson();

    public AgentCore(PromptBuilder promptBuilder, JsonObject llmConfig) {
        this(promptBuilder, llmConfig, null);
    }

    public AgentCore(PromptBuilder promptBuilder, JsonObject llmConfig, PreferencesStore preferencesStore) {
        this.promptBuilder = promptBuilder;
        this.apiKey = llmConfig.get("apiKey").getAsString();
        this.baseUrl = llmConfig.get("baseUrl").getAsString();
        this.model = llmConfig.get("model").getAsString();
        this.maxTokens = llmConfig.get("maxTokens").getAsInt();
        this.temperature = llmConfig.get("temperature").getAsDouble();
        this.endpointStyle = llmConfig.has("endpointStyle")
                ? llmConfig.get("endpointStyle").getAsString()
                : STYLE_OPENAI;
        this.configEffort = (llmConfig.has("effort") && !llmConfig.get("effort").isJsonNull())
                ? llmConfig.get("effort").getAsString()
                : "medium";
        this.preferencesStore = preferencesStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Three-tier resolution:
     *   1. PreferencesStore (runtime override via /effort) — read raw JSON to distinguish
     *      "explicitly unset" from "explicitly set to medium". PreferencesStore.load() normalizes
     *      blank → "medium", which would otherwise prevent fall-through to config.
     *   2. llmConfig.effort (config.json default set during setup)
     *   3. Hardcoded fallback "medium"
     */
    private String resolveEffort() {
        // 1) Preferences override (set by /effort) — read raw to detect "unset"
        String prefEffort = readRawPreferenceEffort();
        if (isValidEffort(prefEffort)) return prefEffort;
        // 2) Config default
        if (isValidEffort(configEffort)) return configEffort;
        // 3) Fallback
        return "medium";
    }

    /**
     * Read preferences.json's "effort" field directly, bypassing PreferencesStore.load()'s
     * blank → "medium" normalization (PreferencesStore is intentionally not modified here).
     * Returns null if the file is missing, unreadable, or the field is absent/blank/null.
     */
    private String readRawPreferenceEffort() {
        if (preferencesStore == null) return null;
        try {
            // Reach the private dataDir Path on PreferencesStore via reflection — we are
            // forbidden from adding an accessor, so this is the least-invasive option.
            java.lang.reflect.Field f = PreferencesStore.class.getDeclaredField("dataDir");
            f.setAccessible(true);
            Path dataDir = (Path) f.get(preferencesStore);
            Path p = dataDir.resolve("preferences.json");
            if (!Files.exists(p)) return null;
            String json = Files.readString(p, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return null;
            JsonElement e = root.getAsJsonObject().get("effort");
            if (e == null || e.isJsonNull()) return null;
            String s = e.getAsString();
            return (s == null || s.isBlank()) ? null : s;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isValidEffort(String e) {
        return e != null && (e.equals("low") || e.equals("medium") || e.equals("high"));
    }

    /** Map effort → Gemini thinkingBudget (tokens). 0 disables thinking. */
    private int effortToBudget(String effort) {
        return switch (effort) {
            case "low" -> 0;
            case "high" -> 8192;
            default -> 1024;  // medium
        };
    }

    public String chat(String userMessage) throws IOException, InterruptedException {
        return chat(userMessage, List.of());
    }

    public String chat(String userMessage, List<ConversationStore.Message> history) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(userMessage, history);
        HttpResponse<String> response = sendHttp(request);
        int status = response.statusCode();

        if (status == 429) {
            Thread.sleep(2000);
            response = sendHttp(request);
            status = response.statusCode();
            if (status == 429) {
                throw new IOException("LLM rate limited (HTTP 429) after retry");
            }
        }

        if (status >= 500) {
            throw new IOException("LLM server error: HTTP " + status);
        }
        if (status >= 400) {
            String body = response.body() == null ? "" : response.body();
            String bodyTrimmed = body.length() > 500 ? body.substring(0, 500) : body;
            throw new IOException("LLM error: HTTP " + status + " body=" + bodyTrimmed);
        }

        String body = response.body() == null ? "" : response.body();
        try {
            return parseAssistantContent(body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Failed to parse LLM response: " + head);
        }
    }

    String buildRequestBody(String userMessage) {
        return buildRequestBody(userMessage, List.of());
    }

    String buildRequestBody(String userMessage, List<ConversationStore.Message> history) {
        if (STYLE_GEMINI_NATIVE.equals(endpointStyle)) {
            return buildGeminiNativeBody(userMessage, history);
        }
        return buildOpenAiBody(userMessage, history);
    }

    HttpRequest buildRequest(String userMessage) {
        return buildRequest(userMessage, List.of());
    }

    HttpRequest buildRequest(String userMessage, List<ConversationStore.Message> history) {
        String json = buildRequestBody(userMessage, history);
        URI uri;
        if (STYLE_GEMINI_NATIVE.equals(endpointStyle)) {
            String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            uri = URI.create(trimmed + "/models/" + model + ":generateContent");
        } else {
            uri = URI.create(baseUrl);
        }

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        if (STYLE_GEMINI_NATIVE.equals(endpointStyle)) {
            b.header("x-goog-api-key", apiKey);
        } else {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return b.build();
    }

    HttpResponse<String> sendHttp(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ----- OpenAI Chat Completions format -----

    private String buildOpenAiBody(String userMessage, List<ConversationStore.Message> history) {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        if (history != null) {
            for (ConversationStore.Message m : history) {
                JsonObject hm = new JsonObject();
                hm.addProperty("role", m.role());
                hm.addProperty("content", m.content());
                messages.add(hm);
            }
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        String effort = resolveEffort();
        // OpenRouter & OpenAI-compat: pass reasoning_effort for models that support it
        body.addProperty("reasoning_effort", effort);

        return gson.toJson(body);
    }

    // ----- Gemini native generateContent format -----

    private String buildGeminiNativeBody(String userMessage, List<ConversationStore.Message> history) {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        JsonObject body = new JsonObject();

        // systemInstruction (separate from contents in Gemini native format)
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sys = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysPart = new JsonObject();
            sysPart.addProperty("text", systemPrompt);
            sysParts.add(sysPart);
            sys.add("parts", sysParts);
            body.add("systemInstruction", sys);
        }

        // contents: history + current user message. Role map: assistant → model
        JsonArray contents = new JsonArray();
        if (history != null) {
            for (ConversationStore.Message m : history) {
                contents.add(makeGeminiTurn(mapRole(m.role()), m.content()));
            }
        }
        contents.add(makeGeminiTurn("user", userMessage));
        body.add("contents", contents);

        JsonObject genCfg = new JsonObject();
        genCfg.addProperty("temperature", temperature);
        genCfg.addProperty("maxOutputTokens", maxTokens);
        String effort = resolveEffort();
        if (model != null && model.toLowerCase().startsWith("gemini-3")) {
            JsonObject thinkingCfg = new JsonObject();
            thinkingCfg.addProperty("thinkingBudget", effortToBudget(effort));
            genCfg.add("thinkingConfig", thinkingCfg);
        }
        body.add("generationConfig", genCfg);

        return gson.toJson(body);
    }

    private JsonObject makeGeminiTurn(String role, String text) {
        JsonObject turn = new JsonObject();
        turn.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        turn.add("parts", parts);
        return turn;
    }

    private String mapRole(String openAiRole) {
        if ("assistant".equals(openAiRole)) return "model";
        return "user";
    }

    // ----- Response parsing -----

    private String parseAssistantContent(String body) throws IOException {
        JsonElement root = JsonParser.parseString(body);
        if (STYLE_GEMINI_NATIVE.equals(endpointStyle)) {
            return parseGeminiNativeContent(root, body);
        }
        return parseOpenAiContent(root, body);
    }

    private String parseOpenAiContent(JsonElement root, String body) throws IOException {
        JsonElement content = root.getAsJsonObject()
                .getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content");
        if (content == null || content.isJsonNull()) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Failed to parse LLM response: " + head);
        }
        return content.getAsString();
    }

    private String parseGeminiNativeContent(JsonElement root, String body) throws IOException {
        JsonArray candidates = root.getAsJsonObject().getAsJsonArray("candidates");
        if (candidates == null || candidates.size() == 0) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Gemini response has no candidates: " + head);
        }
        JsonObject cand0 = candidates.get(0).getAsJsonObject();
        JsonObject content = cand0.getAsJsonObject("content");
        if (content == null) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Gemini candidate missing content: " + head);
        }
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null || parts.size() == 0) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Gemini content missing parts: " + head);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement p : parts) {
            JsonObject partObj = p.getAsJsonObject();
            JsonElement thought = partObj.get("thought");
            if (thought != null && thought.getAsBoolean()) continue;
            JsonElement t = partObj.get("text");
            if (t != null && !t.isJsonNull()) sb.append(t.getAsString());
        }
        return sb.toString();
    }
}
