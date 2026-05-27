package com.healthcoach.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Sends chat requests to an OpenAI-compatible LLM endpoint, returning the raw assistant content. */
public class AgentCore {

    private final PromptBuilder promptBuilder;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final Gson gson = new Gson();

    /** Construct an AgentCore from the supplied PromptBuilder and llmConfig JSON object. */
    public AgentCore(PromptBuilder promptBuilder, JsonObject llmConfig) {
        this.promptBuilder = promptBuilder;
        this.apiKey = llmConfig.get("apiKey").getAsString();
        this.baseUrl = llmConfig.get("baseUrl").getAsString();
        this.model = llmConfig.get("model").getAsString();
        this.maxTokens = llmConfig.get("maxTokens").getAsInt();
        this.temperature = llmConfig.get("temperature").getAsDouble();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Send a chat request to the LLM API and return the raw assistant message content. */
    public String chat(String userMessage) throws IOException, InterruptedException {
        return chat(userMessage, List.of());
    }

    /** Same as chat(userMessage) but with prior turns injected between system prompt and the new user message. */
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
            JsonElement root = JsonParser.parseString(body);
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
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            String head = body.length() > 200 ? body.substring(0, 200) : body;
            throw new IOException("Failed to parse LLM response: " + head);
        }
    }

    /** Build the JSON request body for a given user message (no history). */
    String buildRequestBody(String userMessage) {
        return buildRequestBody(userMessage, List.of());
    }

    /** Build the JSON request body, splicing prior turns between system prompt and new user message. */
    String buildRequestBody(String userMessage, List<ConversationStore.Message> history) {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();

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

        return gson.toJson(body);
    }

    /** Build the prepared HttpRequest (POST to baseUrl) for a given user message (no history). */
    HttpRequest buildRequest(String userMessage) {
        return buildRequest(userMessage, List.of());
    }

    /** Build the prepared HttpRequest with history spliced in. */
    HttpRequest buildRequest(String userMessage, List<ConversationStore.Message> history) {
        String json = buildRequestBody(userMessage, history);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    /** Visible for testing — performs the actual HTTP send. Override in tests to mock. */
    HttpResponse<String> sendHttp(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
