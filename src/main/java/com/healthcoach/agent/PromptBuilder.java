package com.healthcoach.agent;

import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.UserProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Assembles the agent's full system prompt by concatenating the persona, user profile,
 * long-term memory, skills index, today's log summary, and the self-learning patch protocol.
 */
public class PromptBuilder {

    private static final String SECTION_SEPARATOR = "\n\n---\n\n";

    private static final String PATCH_INSTRUCTIONS = ""
            + "# 工具指令（你必須用這些標記來執行動作，純文字回覆不會改變任何資料）\n\n"
            + "所有 <PATCH> 和 <LOG> 標記會被系統解析執行，然後從顯示給使用者的訊息中移除。\n"
            + "重要：如果使用者要求修改資料（刪餐、改體重、記一餐等），你必須在回覆中包含對應的標記，否則不會有任何效果。\n\n"
            + "## <LOG> — 記錄一餐\n\n"
            + "使用者說吃了什麼，你就下 LOG。date 欄位可選，省略或空白 = 今天，填 YYYY-MM-DD 可寫入任意日期。\n"
            + "今天：<LOG>{\"time\":\"12:30\",\"description\":\"雞腿便當\",\"estimatedKcal\":850,\"proteinG\":35,\"carbsG\":95,\"fatG\":28}</LOG>\n"
            + "指定日期：<LOG>{\"date\":\"2026-05-28\",\"time\":\"12:00\",\"description\":\"雞胸便當\",\"estimatedKcal\":700,\"proteinG\":50,\"carbsG\":60,\"fatG\":20}</LOG>\n\n"
            + "## <PATCH> 刪除用餐紀錄\n\n"
            + "使用者說「刪掉」「多記了」「重複」時，你必須下這個 PATCH，不能只用文字回覆。\n"
            + "value 是 /today 中 #N 顯示的索引（0-based）。\n"
            + "範例 — 使用者說「大腸麵線記重複了，刪掉一筆」，/today 顯示 #0 和 #1 都是大腸麵線：\n"
            + "<PATCH>{\"target\":\"daily_log\",\"action\":\"remove_meal\",\"value\":1}</PATCH>\n\n"
            + "## <PATCH> 其他 target\n\n"
            + "- \"user_profile\"：<PATCH>{\"target\":\"user_profile\",\"action\":\"update\",\"field\":\"weightKg\",\"value\":70.5}</PATCH>\n"
            + "- \"memory\"（add/remove/replace）：<PATCH>{\"target\":\"memory\",\"action\":\"add\",\"content\":\"使用者不吃牛肉\"}</PATCH>\n"
            + "  remove: content 為關鍵字；replace: content 為 \"舊|||新\"\n"
            + "- \"skill/{name}\"（append/replace）：<PATCH>{\"target\":\"skill/nutrition-advice\",\"action\":\"append\",\"content\":\"...\"}</PATCH>\n"
            + "- \"preferences\"（set_meals/set_workout/set_weekly/set_timezone/clear_meals/clear_workout）\n\n"
            + "## 原則\n\n"
            + "- 使用者要求動作 → 必須下對應的標記。光說「已處理」但沒下標記 = 沒有執行。\n"
            + "- 只在資訊明確時才下 PATCH；不確定就先問。\n"
            + "- PATCH/LOG 不會顯示給使用者，使用者只看到標記以外的文字。\n";

    private final String soulContent;
    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;

    /** Construct the builder; eagerly loads soul.md from the classpath once and caches it. */
    public PromptBuilder(MemoryStore memoryStore, SkillManager skillManager, DailyLogStore dailyLogStore)
            throws IOException {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("soul.md")) {
            if (is == null) {
                throw new IOException("soul.md not found on classpath");
            }
            this.soulContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Assemble the full system prompt from all memory layers. */
    public String buildSystemPrompt() {
        UserProfile profile = memoryStore.loadUserProfile();
        return String.join(
                SECTION_SEPARATOR,
                soulContent,
                memoryStore.getUserProfileSummary(),
                memoryStore.getMemorySummary(),
                skillManager.getSkillsIndexText(),
                dailyLogStore.getTodaySummaryText(profile),
                PATCH_INSTRUCTIONS);
    }
}
