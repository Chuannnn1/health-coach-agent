package com.healthcoach.bot;

import com.healthcoach.agent.ConversationStore;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.MemoryData;
import com.healthcoach.model.MemoryEntry;
import com.healthcoach.model.SkillSummary;
import com.healthcoach.model.UserProfile;

import java.util.List;
import java.util.Locale;

public class SlashRouter {

    public sealed interface Action {
        record Reply(String text) implements Action {}
        record DelegateToAgent(String syntheticUserMessage) implements Action {}
        record NotHandled() implements Action {}
    }

    private static final String HELP =
            "Coach 指令：\n" +
            "/start — 啟動並訂閱提醒\n" +
            "/new — 開始新對話（清空最近上下文）\n" +
            "/profile — 看你的個人資料\n" +
            "/today — 看今天吃了什麼、還剩多少 kcal\n" +
            "/memory — 看 Coach 記得的長期事實\n" +
            "/skills — 列出知識模組\n" +
            "/skill <名稱> — 看某個知識模組的內容\n" +
            "/analyze — 分析今日熱量狀況並建議下一餐\n" +
            "/suggest <早餐|午餐|晚餐|宵夜> — 根據偏好給建議\n" +
            "/chart — 用 markdown 表格呈現本週飲食趨勢\n" +
            "/help — 顯示這份指令清單";

    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;
    private final ConversationStore conversationStore;

    public SlashRouter(MemoryStore memoryStore, SkillManager skillManager,
                       DailyLogStore dailyLogStore, ConversationStore conversationStore) {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        this.conversationStore = conversationStore;
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
            case "/analyze":
                return new Action.DelegateToAgent(buildAnalyzePrompt());
            case "/suggest":
                return new Action.DelegateToAgent(buildSuggestPrompt(arg));
            case "/chart":
                return new Action.DelegateToAgent(buildChartPrompt());
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
            for (MealEntry m : log.meals) {
                sb.append(String.format("• %s  %s — %d kcal (P%d C%d F%d)\n",
                        m.time, m.description, m.estimatedKcal, m.proteinG, m.carbsG, m.fatG));
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
