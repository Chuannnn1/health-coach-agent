package com.healthcoach.chart;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.DailySummary;
import com.healthcoach.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ChartService {
    private static final Logger log = LoggerFactory.getLogger(ChartService.class);
    private static final String QUICKCHART_URL = "https://quickchart.io/chart";
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d");
    private static final int CHART_WIDTH = 600;
    private static final int CHART_HEIGHT = 400;

    private final DailyLogStore dailyLogStore;
    private final MemoryStore memoryStore;
    private final HttpClient httpClient;
    private final String apiKey; // nullable, free tier works without it
    private final Gson gson = new Gson();

    public ChartService(DailyLogStore dailyLogStore, MemoryStore memoryStore, String apiKey) {
        this.dailyLogStore = dailyLogStore;
        this.memoryStore = memoryStore;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ChartResult(byte[] png, String caption) {}

    public ChartResult generateWeeklyChart() throws IOException, InterruptedException {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(6);
        List<DailyLog> logs = dailyLogStore.loadDateRange(weekAgo, today);
        UserProfile profile = memoryStore.loadUserProfile();

        if (logs.isEmpty()) {
            return null;
        }

        for (DailyLog dl : logs) {
            dailyLogStore.recalculateSummary(dl, profile);
        }

        String chartConfig = buildChartConfig(logs, profile.targetCalories, weekAgo, today);
        byte[] png = fetchChart(chartConfig);

        int daysWithData = logs.size();
        int avgKcal = logs.stream().mapToInt(l -> l.dailySummary.totalKcal).sum() / daysWithData;
        int avgProtein = logs.stream().mapToInt(l -> l.dailySummary.totalProteinG).sum() / daysWithData;
        String caption = String.format(
                "本週飲食趨勢（%s ~ %s）\n%d 天有紀錄，平均 %d kcal / %d g 蛋白質",
                weekAgo.format(SHORT_DATE), today.format(SHORT_DATE),
                daysWithData, avgKcal, avgProtein);
        if (profile.targetCalories > 0) {
            int diff = avgKcal - profile.targetCalories;
            caption += String.format("\n目標 %d kcal，平均%s %d kcal",
                    profile.targetCalories,
                    diff >= 0 ? "超出" : "低於",
                    Math.abs(diff));
        }

        return new ChartResult(png, caption);
    }

    private String buildChartConfig(List<DailyLog> logs, int targetKcal,
                                     LocalDate from, LocalDate to) {
        JsonArray labels = new JsonArray();
        JsonArray kcalData = new JsonArray();
        JsonArray proteinData = new JsonArray();

        int logIdx = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            labels.add(d.format(SHORT_DATE));
            if (logIdx < logs.size()) {
                DailyLog dl = logs.get(logIdx);
                LocalDate logDate = LocalDate.parse(dl.date);
                if (logDate.equals(d)) {
                    kcalData.add(dl.dailySummary.totalKcal);
                    proteinData.add(dl.dailySummary.totalProteinG);
                    logIdx++;
                    continue;
                }
            }
            kcalData.add(0);
            proteinData.add(0);
        }

        JsonObject kcalDataset = new JsonObject();
        kcalDataset.addProperty("label", "熱量 (kcal)");
        kcalDataset.add("data", kcalData);
        kcalDataset.addProperty("backgroundColor", "rgba(54, 162, 235, 0.7)");
        kcalDataset.addProperty("borderColor", "rgba(54, 162, 235, 1)");
        kcalDataset.addProperty("borderWidth", 1);
        kcalDataset.addProperty("yAxisID", "y");

        JsonObject proteinDataset = new JsonObject();
        proteinDataset.addProperty("label", "蛋白質 (g)");
        proteinDataset.add("data", proteinData);
        proteinDataset.addProperty("backgroundColor", "rgba(255, 99, 132, 0.7)");
        proteinDataset.addProperty("borderColor", "rgba(255, 99, 132, 1)");
        proteinDataset.addProperty("borderWidth", 1);
        proteinDataset.addProperty("yAxisID", "y2");

        JsonArray datasets = new JsonArray();
        datasets.add(kcalDataset);
        datasets.add(proteinDataset);

        JsonObject data = new JsonObject();
        data.add("labels", labels);
        data.add("datasets", datasets);

        // Y axes
        JsonObject yAxis = new JsonObject();
        yAxis.addProperty("position", "left");
        yAxis.addProperty("beginAtZero", true);
        JsonObject yTitle = new JsonObject();
        yTitle.addProperty("display", true);
        yTitle.addProperty("text", "kcal");
        yAxis.add("title", yTitle);

        JsonObject y2Axis = new JsonObject();
        y2Axis.addProperty("position", "right");
        y2Axis.addProperty("beginAtZero", true);
        JsonObject y2Title = new JsonObject();
        y2Title.addProperty("display", true);
        y2Title.addProperty("text", "蛋白質 (g)");
        y2Axis.add("title", y2Title);
        JsonObject y2Grid = new JsonObject();
        y2Grid.addProperty("drawOnChartArea", false);
        y2Axis.add("grid", y2Grid);

        JsonObject scales = new JsonObject();
        scales.add("y", yAxis);
        scales.add("y2", y2Axis);

        // Plugins
        JsonObject plugins = new JsonObject();
        JsonObject titlePlugin = new JsonObject();
        titlePlugin.addProperty("display", true);
        titlePlugin.addProperty("text", "本週飲食趨勢");
        JsonObject titleFont = new JsonObject();
        titleFont.addProperty("size", 16);
        titlePlugin.add("font", titleFont);
        plugins.add("title", titlePlugin);

        // Target line annotation
        if (targetKcal > 0) {
            JsonObject annotation = new JsonObject();
            JsonObject annotations = new JsonObject();
            JsonObject targetLine = new JsonObject();
            targetLine.addProperty("type", "line");
            targetLine.addProperty("yScaleID", "y");
            targetLine.addProperty("yMin", targetKcal);
            targetLine.addProperty("yMax", targetKcal);
            targetLine.addProperty("borderColor", "rgba(255, 159, 64, 0.9)");
            targetLine.addProperty("borderWidth", 2);
            JsonArray dash = new JsonArray();
            dash.add(6);
            dash.add(4);
            targetLine.add("borderDash", dash);
            JsonObject lineLabel = new JsonObject();
            lineLabel.addProperty("display", true);
            lineLabel.addProperty("content", "目標 " + targetKcal + " kcal");
            lineLabel.addProperty("position", "start");
            targetLine.add("label", lineLabel);
            annotations.add("targetLine", targetLine);
            annotation.add("annotations", annotations);
            plugins.add("annotation", annotation);
        }

        JsonObject options = new JsonObject();
        options.add("scales", scales);
        options.add("plugins", plugins);

        JsonObject chart = new JsonObject();
        chart.addProperty("type", "bar");
        chart.add("data", data);
        chart.add("options", options);

        JsonObject body = new JsonObject();
        body.addProperty("version", "4");
        body.add("chart", chart);
        body.addProperty("width", CHART_WIDTH);
        body.addProperty("height", CHART_HEIGHT);
        body.addProperty("backgroundColor", "white");

        return gson.toJson(body);
    }

    private byte[] fetchChart(String requestBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(QUICKCHART_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-QuickChart-Key", apiKey);
        }

        HttpResponse<byte[]> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            String errBody = new String(response.body(), StandardCharsets.UTF_8);
            String trimmed = errBody.length() > 300 ? errBody.substring(0, 300) : errBody;
            throw new IOException("QuickChart error: HTTP " + response.statusCode() + " " + trimmed);
        }

        return response.body();
    }
}
