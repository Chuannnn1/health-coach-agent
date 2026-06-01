package com.healthcoach.bot;

import com.healthcoach.agent.ConversationStore;
import com.healthcoach.chart.ChartService;
import com.healthcoach.scheduler.CronScheduler;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.MemoryData;
import com.healthcoach.model.MemoryEntry;
import com.healthcoach.model.Preferences;
import com.healthcoach.model.SkillSummary;
import com.healthcoach.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SlashRouter {
    private static final Logger log = LoggerFactory.getLogger(SlashRouter.class);

    public sealed interface Action {
        record Reply(String text) implements Action {}
        record DelegateToAgent(String syntheticUserMessage) implements Action {}
        record SendPhoto(byte[] image, String caption) implements Action {}
        record NotHandled() implements Action {}
    }

    private static final String HELP =
            "Coach 指令：\n" +
            "/start — 啟動並訂閱提醒\n" +
            "/stop — 中斷當前回覆\n" +
            "/setup — 步驟式設定個人資料（互動表單）\n" +
            "/new — 開始新對話（清空最近上下文）\n" +
            "/profile — 看你的個人資料\n" +
            "/today — 看今天吃了什麼、還剩多少 kcal\n" +
            "/memory — 看 Coach 記得的長期事實\n" +
            "/skills — 列出知識模組\n" +
            "/skill <名稱> — 看某個知識模組的內容\n" +
            "/reminders — 看 / 改用餐 & 訓練提醒（用法：/reminders ?）\n" +
            "/effort — 設定模型 reasoning effort（low/medium/high）\n" +
            "/analyze — 分析今日熱量狀況並建議下一餐\n" +
            "/suggest <早餐|午餐|晚餐|宵夜> — 根據偏好給建議\n" +
            "/resume — 查看目前對話上下文（重啟後自動恢復）\n" +
            "/status — 當前 session 狀態總覽\n" +
            "/chart — 本週飲食趨勢圖（有 API 則送 PNG，否則文字表格）\n" +
            "/help — 顯示這份指令清單";

    private static final String REMINDERS_USAGE =
            "用法：\n" +
            "/reminders                    — 看目前設定\n" +
            "/reminders set meals 07:30,12:00,18:00\n" +
            "/reminders set workout 20:00\n" +
            "/reminders set weekly SUN 21:00\n" +
            "/reminders set timezone Asia/Taipei\n" +
            "/reminders clear meals|workout\n" +
            "/reminders preset 3meals|2meals|if|none\n" +
            "\n或直接跟我說「以後只要午餐跟晚餐提醒」我也會改。";

    private static final String EFFORT_USAGE =
            "用法：/effort low|medium|high\n" +
            "目前選項：\n" +
            "  low    — 直覺回覆，最快（Gemini 3: thinkingBudget=0）\n" +
            "  medium — 預設，平衡品質與速度（thinkingBudget=1024）\n" +
            "  high   — 深度推理，較慢（thinkingBudget=8192）\n" +
            "Gemma 系列不支援 reasoning，設定會被忽略。";

    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;
    private final ConversationStore conversationStore;
    private final PreferencesStore preferencesStore;  // nullable for legacy tests
    private final Runnable onPreferencesChanged;       // nullable
    private ChartService chartService;                 // nullable
    private CronScheduler cronScheduler;               // nullable
    private String modelName = "";                     // set from config

    /** Legacy constructor (no /reminders support). */
    public SlashRouter(MemoryStore memoryStore, SkillManager skillManager,
                       DailyLogStore dailyLogStore, ConversationStore conversationStore) {
        this(memoryStore, skillManager, dailyLogStore, conversationStore, null, null);
    }

    /** Full constructor with /reminders support. */
    public SlashRouter(MemoryStore memoryStore, SkillManager skillManager,
                       DailyLogStore dailyLogStore, ConversationStore conversationStore,
                       PreferencesStore preferencesStore, Runnable onPreferencesChanged) {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        this.conversationStore = conversationStore;
        this.preferencesStore = preferencesStore;
        this.onPreferencesChanged = onPreferencesChanged;
    }

    public void setChartService(ChartService chartService) {
        this.chartService = chartService;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName == null ? "" : modelName;
    }

    public void setCronScheduler(CronScheduler cronScheduler) {
        this.cronScheduler = cronScheduler;
    }

    /** Dispatch a possible slash command. Returns NotHandled if text is not a slash command we know. */
    public Action route(String chatId, String rawText) {
        if (rawText == null || !rawText.startsWith("/")) return new Action.NotHandled();
        String[] parts = rawText.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/help":
                return new Action.Reply(HELP);
            case "/new":
                conversationStore.clear(chatId);
                return new Action.Reply("好，重新開始 ✨ 上下文已清空。");
            case "/profile":
                return new Action.Reply(renderProfile());
            case "/today":
                return new Action.Reply(renderToday());
            case "/memory":
                return new Action.Reply(renderMemory());
            case "/skills":
                return new Action.Reply(renderSkillList());
            case "/skill":
                return new Action.Reply(renderSkill(arg));
            case "/reminders":
                return new Action.Reply(handleReminders(arg));
            case "/effort":
                return new Action.Reply(handleEffort(arg));
            case "/resume":
                return new Action.Reply(renderResume(chatId));
            case "/status":
                return new Action.Reply(renderStatus(chatId));
            case "/analyze":
                return new Action.DelegateToAgent(buildAnalyzePrompt());
            case "/suggest":
                return new Action.DelegateToAgent(buildSuggestPrompt(arg));
            case "/chart":
                return handleChart();
            case "/test_reminder":
                return new Action.Reply(handleTestReminder(chatId, arg));
            default:
                return new Action.NotHandled();
        }
    }

    public static String helpText() {
        return HELP;
    }

    private String renderProfile() {
        UserProfile p = memoryStore.loadUserProfile();
        if (p.name == null || p.name.isBlank()) {
            return "你還沒設定個人資料。請告訴我你的身高體重年齡性別與目標，我會幫你算 BMR / TDEE / 三大營養素。";
        }
        return String.format(
                "👤 個人資料\n" +
                "姓名：%s\n" +
                "年齡：%d  性別：%s\n" +
                "身高：%.1f cm  體重：%.1f kg\n" +
                "活動量：%s   目標：%s\n" +
                "BMR：%d kcal   TDEE：%d kcal\n" +
                "目標熱量：%d kcal\n" +
                "蛋白質：%d g  碳水：%d g  脂肪：%d g\n" +
                "飲食限制：%s\n" +
                "備註：%s",
                p.name, p.age, p.gender, p.heightCm, p.weightKg,
                p.activityLevel, p.goal, p.bmr, p.tdee,
                p.targetCalories, p.targetProteinG, p.targetCarbsG, p.targetFatG,
                p.dietaryRestrictions == null || p.dietaryRestrictions.isEmpty() ? "（無）" : String.join("、", p.dietaryRestrictions),
                p.notes == null || p.notes.isBlank() ? "（無）" : p.notes);
    }

    private String renderToday() {
        UserProfile p = memoryStore.loadUserProfile();
        DailyLog log = dailyLogStore.loadToday();
        dailyLogStore.recalculateSummary(log, p);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 今日紀錄（").append(log.date).append("）\n\n");
        if (log.meals == null || log.meals.isEmpty()) {
            sb.append("還沒記錄任何一餐。\n");
        } else {
            for (int i = 0; i < log.meals.size(); i++) {
                MealEntry m = log.meals.get(i);
                sb.append(String.format("#%d  %s  %s — %d kcal (P%d C%d F%d)\n",
                        i, m.time, m.description, m.estimatedKcal, m.proteinG, m.carbsG, m.fatG));
            }
        }
        if (log.workout != null) {
            sb.append("\n💪 ").append(log.workout.type).append("：")
              .append(log.workout.description).append("（").append(log.workout.durationMin).append(" 分鐘）\n");
        }
        sb.append("\n").append(renderProgressBar(log.dailySummary.totalKcal, log.dailySummary.targetKcal, "熱量"));
        sb.append("\n").append(renderProgressBar(log.dailySummary.totalProteinG, log.dailySummary.targetProteinG, "蛋白質"));
        sb.append("\n剩餘 ").append(log.dailySummary.kcalRemaining).append(" kcal");
        return sb.toString();
    }

    private static String renderProgressBar(int current, int target, String label) {
        if (target <= 0) return String.format("%s：%d kcal（目標未設定）", label, current);
        int pct = (int) Math.round(100.0 * current / target);
        int filled = Math.min(20, Math.max(0, pct / 5));
        String bar = "█".repeat(filled) + "░".repeat(20 - filled);
        return String.format("%s：%d / %d (%d%%)\n[%s]", label, current, target, pct, bar);
    }

    private String renderMemory() {
        MemoryData m = memoryStore.loadMemory();
        if (m.entries == null || m.entries.isEmpty()) return "🧠 長期記憶：（空）";
        StringBuilder sb = new StringBuilder("🧠 長期記憶（最多 ").append(m.maxEntries).append(" 條）：\n");
        int i = 1;
        for (MemoryEntry e : m.entries) {
            sb.append(i++).append(". ").append(e.content).append("\n");
        }
        return sb.toString().trim();
    }

    private String renderSkillList() {
        List<SkillSummary> skills = skillManager.listSkills();
        if (skills.isEmpty()) return "📚 知識模組：（無）";
        StringBuilder sb = new StringBuilder("📚 知識模組：\n");
        for (SkillSummary s : skills) {
            sb.append("• ").append(s.name).append(" — ").append(s.description).append("\n");
        }
        sb.append("\n用 /skill <名稱> 查看內容。");
        return sb.toString();
    }

    private String renderSkill(String name) {
        if (name == null || name.isBlank()) {
            return "用法：/skill <名稱>。先用 /skills 看可用清單。";
        }
        try {
            String content = skillManager.loadSkill(name);
            if (content.length() > 3500) {
                content = content.substring(0, 3500) + "\n…（已截斷，完整內容在 data/skills/" + name + "/SKILL.md）";
            }
            return "📖 " + name + "\n\n" + content;
        } catch (IllegalArgumentException e) {
            return "找不到知識模組「" + name + "」。用 /skills 看可用清單。";
        }
    }

    private String renderResume(String chatId) {
        List<ConversationStore.Message> history = conversationStore.recent(chatId);
        if (history.isEmpty()) {
            return "目前沒有對話紀錄。重啟後如有先前的對話會自動載入。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("💬 對話上下文（").append(history.size()).append(" 則）：\n\n");
        int start = Math.max(0, history.size() - 10);
        if (start > 0) {
            sb.append("… 較早的 ").append(start).append(" 則已省略\n\n");
        }
        for (int i = start; i < history.size(); i++) {
            ConversationStore.Message m = history.get(i);
            String label = "user".equals(m.role()) ? "你" : "Coach";
            String text = m.content();
            if (text.length() > 120) text = text.substring(0, 120) + "…";
            sb.append(label).append("：").append(text).append("\n\n");
        }
        sb.append("---\n用 /new 清空上下文開始新對話。");
        return sb.toString();
    }

    private String renderStatus(String chatId) {
        StringBuilder sb = new StringBuilder("📊 Session 狀態\n\n");

        // Model & effort
        sb.append("模型：").append(modelName.isEmpty() ? "（未設定）" : modelName).append("\n");
        String effort = "medium";
        if (preferencesStore != null) {
            effort = preferencesStore.load().effort;
        }
        sb.append("Effort：").append(effort).append("\n\n");

        // Context
        int contextSize = conversationStore.size(chatId);
        int maxContext = 20;
        sb.append("對話上下文：").append(contextSize).append(" / ").append(maxContext).append(" 則");
        if (contextSize == 0) {
            sb.append("（空）");
        }
        sb.append("\n\n");

        // Profile summary
        UserProfile p = memoryStore.loadUserProfile();
        if (p.name != null && !p.name.isBlank()) {
            sb.append("使用者：").append(p.name);
            if (p.targetCalories > 0) {
                sb.append("（目標 ").append(p.targetCalories).append(" kcal）");
            }
            sb.append("\n");
        } else {
            sb.append("使用者：未設定（用 /setup 開始）\n");
        }

        // Today's intake
        DailyLog log = dailyLogStore.loadToday();
        dailyLogStore.recalculateSummary(log, p);
        int meals = log.meals == null ? 0 : log.meals.size();
        sb.append("今日：").append(meals).append(" 餐，")
          .append(log.dailySummary.totalKcal).append(" kcal\n");

        // Memory & skills
        sb.append("長期記憶：").append(memoryStore.loadMemory().entries == null ? 0 : memoryStore.loadMemory().entries.size()).append(" 條\n");
        sb.append("知識模組：").append(skillManager.listSkills().size()).append(" 個\n");

        // Chart service
        sb.append("圖表服務：").append(chartService != null ? "QuickChart (已啟用)" : "文字模式").append("\n");

        return sb.toString();
    }

    private String handleTestReminder(String chatId, String arg) {
        if (cronScheduler == null) {
            return "CronScheduler 未連接。";
        }
        List<String> types = new ArrayList<>();
        if (arg == null || arg.isBlank() || "all".equals(arg)) {
            types.addAll(List.of("breakfast", "lunch", "dinner", "workout"));
        } else {
            for (String t : arg.split("[,\\s]+")) {
                String trimmed = t.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) types.add(trimmed);
            }
        }
        int sent = 0;
        for (String type : types) {
            try {
                cronScheduler.sendReminder(chatId, type);
                sent++;
            } catch (Exception e) {
                log.warn("test_reminder failed for {}: {}", type, e.getMessage());
            }
        }
        return "已觸發 " + sent + " 則提醒（" + String.join(", ", types) + "）";
    }

    private String handleReminders(String arg) {
        if (preferencesStore == null) {
            return "提醒設定功能尚未啟用（PreferencesStore not wired）。";
        }
        if (arg == null || arg.isBlank() || arg.equals("?") || arg.equals("help")) {
            return renderReminders() + "\n\n" + REMINDERS_USAGE;
        }
        String[] parts = arg.split("\\s+", 3);
        String verb = parts[0].toLowerCase(Locale.ROOT);

        switch (verb) {
            case "set": {
                if (parts.length < 3) return "用法：/reminders set <field> <value>\n\n" + REMINDERS_USAGE;
                return handleRemindersSet(parts[1].toLowerCase(Locale.ROOT), parts[2]);
            }
            case "clear": {
                if (parts.length < 2) return "用法：/reminders clear meals|workout";
                return handleRemindersClear(parts[1].toLowerCase(Locale.ROOT));
            }
            case "preset": {
                if (parts.length < 2) return "用法：/reminders preset 3meals|2meals|if|none";
                return handleRemindersPreset(parts[1].toLowerCase(Locale.ROOT));
            }
            default:
                return "不認得：" + verb + "\n\n" + REMINDERS_USAGE;
        }
    }

    private String handleRemindersSet(String field, String value) {
        Preferences pref;
        switch (field) {
            case "meals": {
                List<String> times = new ArrayList<>();
                for (String s : value.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) times.add(t);
                }
                pref = preferencesStore.setMealReminders(times);
                break;
            }
            case "workout":
                pref = preferencesStore.setWorkoutReminder(value.trim());
                break;
            case "weekly":
                pref = preferencesStore.setWeeklySummary(value.trim());
                break;
            case "timezone":
                pref = preferencesStore.setTimezone(value.trim());
                break;
            default:
                return "不認得欄位：" + field + "（可用：meals / workout / weekly / timezone）";
        }
        fireReschedule();
        return "已更新。\n\n" + renderReminders(pref);
    }

    private String handleRemindersClear(String field) {
        Preferences pref;
        switch (field) {
            case "meals":
                pref = preferencesStore.setMealReminders(new ArrayList<>());
                break;
            case "workout":
                pref = preferencesStore.setWorkoutReminder("");
                break;
            case "weekly":
                pref = preferencesStore.setWeeklySummary("");
                break;
            default:
                return "不認得欄位：" + field + "（可清 meals / workout / weekly）";
        }
        fireReschedule();
        return "已清空。\n\n" + renderReminders(pref);
    }

    private String handleRemindersPreset(String name) {
        Preferences pref = preferencesStore.load();
        switch (name) {
            case "3meals":
                pref.mealReminders = new ArrayList<>(Arrays.asList("07:30", "12:00", "18:00"));
                break;
            case "2meals":
                pref.mealReminders = new ArrayList<>(Arrays.asList("12:00", "18:30"));
                break;
            case "if":
            case "1meal":
                pref.mealReminders = new ArrayList<>(Arrays.asList("18:00"));
                break;
            case "none":
                pref.mealReminders = new ArrayList<>();
                break;
            default:
                return "不認得 preset：" + name + "（可用：3meals / 2meals / if / none）";
        }
        preferencesStore.save(pref);
        fireReschedule();
        return "已套用 preset " + name + "。\n\n" + renderReminders(pref);
    }

    private String renderReminders() {
        return renderReminders(preferencesStore.load());
    }

    private String renderReminders(Preferences pref) {
        StringBuilder sb = new StringBuilder("⏰ 提醒設定\n");
        sb.append("時區：").append(pref.timezone).append("\n");
        if (pref.mealReminders == null || pref.mealReminders.isEmpty()) {
            sb.append("用餐提醒：（無）\n");
        } else {
            sb.append("用餐提醒：").append(String.join(", ", pref.mealReminders)).append("\n");
        }
        sb.append("訓練提醒：")
          .append(pref.workoutReminder == null || pref.workoutReminder.isBlank() ? "（無）" : pref.workoutReminder)
          .append("\n");
        sb.append("週報：")
          .append(pref.weeklySummary == null || pref.weeklySummary.isBlank() ? "（無）" : pref.weeklySummary);
        return sb.toString();
    }

    private void fireReschedule() {
        if (onPreferencesChanged != null) {
            try { onPreferencesChanged.run(); } catch (Exception ignored) {}
        }
    }

    private String handleEffort(String arg) {
        if (preferencesStore == null) {
            return "effort 設定尚未啟用（PreferencesStore not wired）。";
        }
        if (arg == null || arg.isBlank() || arg.equals("?") || arg.equals("help")) {
            Preferences p = preferencesStore.load();
            return "🧠 目前 effort: " + p.effort + "\n\n" + EFFORT_USAGE;
        }
        String v = arg.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("low") && !v.equals("medium") && !v.equals("high")) {
            return "不認得：" + arg + "\n\n" + EFFORT_USAGE;
        }
        Preferences p = preferencesStore.setEffort(v);
        return "已設定 effort = " + p.effort + "（下次對話生效）";
    }

    private String buildAnalyzePrompt() {
        return "請根據目前載入的「今日紀錄」與「使用者資料」做以下分析（150 字內）：\n" +
               "1. 今天目前熱量與目標的差距\n" +
               "2. 蛋白質達標狀況\n" +
               "3. 接下來該注意什麼\n" +
               "4. 建議下一餐吃什麼（考慮使用者飲食限制與長期記憶中的偏好）\n" +
               "回覆只用文字，不用 markdown 表格，不用下 PATCH。";
    }

    private String buildSuggestPrompt(String arg) {
        String slot = (arg == null || arg.isBlank()) ? "下一餐" : arg;
        return "請根據使用者的目標熱量、剩餘 kcal、飲食限制與長期記憶中的偏好，" +
               "推薦三個「" + slot + "」選項。\n" +
               "每個選項用一行：\n" +
               "<品名> — 約 <kcal> kcal，蛋白質 <g>g（理由一句話）\n" +
               "不要 PATCH/LOG，不要 markdown 表格。";
    }

    private Action handleChart() {
        if (chartService != null) {
            try {
                ChartService.ChartResult result = chartService.generateWeeklyChart();
                if (result != null) {
                    return new Action.SendPhoto(result.png(), result.caption());
                }
                return new Action.Reply("本週沒有任何飲食紀錄，無法產生圖表。先記錄一餐再試試！");
            } catch (Exception e) {
                log.warn("chart generation failed, falling back to text: {}", e.getMessage());
            }
        }
        return new Action.DelegateToAgent(buildChartPrompt());
    }

    private String buildChartPrompt() {
        return "請用 markdown 表格與 ASCII 進度條呈現本週飲食狀況（要可在 Telegram 純文字顯示）：\n\n" +
               "範例輸出：\n" +
               "```\n" +
               "日期    熱量/目標     蛋白質   狀態\n" +
               "5/26    2100/2800    105g     [████░░░░░░░░░░]\n" +
               "5/27    2750/2800    142g     [█████████████░]\n" +
               "```\n\n" +
               "如果只有今天有紀錄，就只列今天並標註其他天「無紀錄」。最後用兩句話總結趨勢。" +
               "不要下 PATCH/LOG。";
    }
}
