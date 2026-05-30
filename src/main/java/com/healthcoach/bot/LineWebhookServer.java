package com.healthcoach.bot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.ConversationStore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.model.ExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class LineWebhookServer implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookServer.class);
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";

    private final String channelSecret;
    private final String channelAccessToken;
    private final int port;
    private final AgentCore agentCore;
    private final PatchExecutor patchExecutor;
    private final ConversationStore conversationStore;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final Set<String> knownUserIds = Collections.synchronizedSet(new HashSet<>());
    private ProfileWizard profileWizard;
    private HttpServer server;

    public LineWebhookServer(String channelSecret, String channelAccessToken, int port,
                             AgentCore agentCore, PatchExecutor patchExecutor,
                             ConversationStore conversationStore) {
        this.channelSecret = channelSecret;
        this.channelAccessToken = channelAccessToken;
        this.port = port;
        this.agentCore = agentCore;
        this.patchExecutor = patchExecutor;
        this.conversationStore = conversationStore;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void setProfileWizard(ProfileWizard wizard) {
        this.profileWizard = wizard;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/callback", this::handleCallback);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("LINE webhook server started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
            log.info("LINE webhook server stopped");
        }
    }

    @Override
    public void sendText(String chatId, String text) {
        pushMessage(chatId, text);
    }

    @Override
    public Set<String> getRegisteredChatIds() {
        return new HashSet<>(knownUserIds);
    }

    public void pushMessage(String userId, String text) {
        if (userId == null || userId.isBlank()) return;
        JsonObject body = new JsonObject();
        body.addProperty("to", userId);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "text");
        msg.addProperty("text", text);
        messages.add(msg);
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PUSH_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + channelAccessToken)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("LINE push failed: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("LINE push error", e);
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = exchange.getRequestHeaders().getFirst("X-Line-Signature");

        if (signature == null || !verifySignature(body, signature)) {
            log.warn("Invalid LINE signature");
            respond(exchange, 403, "Forbidden");
            return;
        }

        respond(exchange, 200, "OK");

        try {
            processEvents(body);
        } catch (Exception e) {
            log.error("Error processing LINE events", e);
        }
    }

    private void processEvents(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray events = root.getAsJsonArray("events");
        if (events == null) return;

        for (JsonElement ev : events) {
            JsonObject event = ev.getAsJsonObject();
            String type = event.get("type").getAsString();
            if (!"message".equals(type)) continue;

            JsonObject message = event.getAsJsonObject("message");
            if (message == null) continue;
            String msgType = message.get("type").getAsString();
            if (!"text".equals(msgType)) continue;

            String userText = message.get("text").getAsString();
            String replyToken = event.get("replyToken").getAsString();
            JsonObject source = event.getAsJsonObject("source");
            String userId = source != null && source.has("userId")
                    ? source.get("userId").getAsString() : "unknown";
            if (!"unknown".equals(userId)) {
                knownUserIds.add(userId);
            }

            handleTextMessage(userId, userText, replyToken);
        }
    }

    private void handleTextMessage(String userId, String userText, String replyToken) {
        // Handle /setup and wizard-active sessions
        if ("/setup".equals(userText.trim()) && profileWizard != null) {
            ProfileWizard.WizardResponse resp = profileWizard.start(userId);
            replyText(replyToken, resp.text());
            return;
        }
        if (profileWizard != null && profileWizard.isActive(userId)) {
            ProfileWizard.WizardResponse resp = profileWizard.handle(userId, userText);
            replyText(replyToken, resp.text());
            return;
        }

        try {
            List<ConversationStore.Message> history = conversationStore.recent(userId);
            conversationStore.appendUser(userId, userText);

            String rawReply = agentCore.chat(userText, history);
            ExecutionResult result = patchExecutor.execute(rawReply);
            String cleanText = result.cleanText;

            if (cleanText == null || cleanText.isBlank()) {
                cleanText = "...";
            }
            if (cleanText.length() > 5000) {
                cleanText = cleanText.substring(0, 4997) + "...";
            }

            conversationStore.appendAssistant(userId, cleanText);
            replyText(replyToken, cleanText);
        } catch (Exception e) {
            log.error("LINE handleTextMessage error for user={}", userId, e);
            replyText(replyToken, "抱歉，暫時無法回覆，請稍後再試。");
        }
    }

    private void replyText(String replyToken, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("replyToken", replyToken);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "text");
        msg.addProperty("text", text);
        messages.add(msg);
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REPLY_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + channelAccessToken)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("LINE reply failed: {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("LINE reply error", e);
        }
    }

    private boolean verifySignature(String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(hash);
            return computed.equals(signature);
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
