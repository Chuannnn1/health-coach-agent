package com.healthcoach.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseSanitizerTest {

    /** Verbatim leak captured from Gemma 4 in production. */
    private static final String PRODUCTION_LEAK = ""
            + "*   User says: \"again hi there ?\"\n"
            + "*   Context: The user is greeting the coach again. No user profile is set yet.\n"
            + "\n"
            + "*   Role: Coach (professional yet friendly, warm but direct).\n"
            + "*   Goal: Get user information (height, weight, age, gender, activity level, goal) to start providing health/fitness guidance.\n"
            + "*   Constraints: Traditional Chinese, short response (<150 words), no meta-headers, no planning bullets, no code fences.\n"
            + "\n"
            + "*   Greeting: \"Hey there!\" or \"Hi again!\"\n"
            + "*   Call to action: Ask for the necessary details.\n"
            + "*   Specifics needed: Height, weight, age, gender, activity level, goal (muscle gain/fat loss/maintenance).嘿！我是 Coach。很高興再次見到你！\n"
            + "\n"
            + "準備好開始改變了嗎？先告訴我你的**身高、體重、年齡、性別、平時的活動量**以及你的**目標**（例如：增肌、減脂或維持健康），我馬上幫你計算 BMR/TDEE 並規劃適合你的三大營養素配比！";

    @Test
    void t1_realProductionLeak() {
        String cleaned = ResponseSanitizer.sanitize(PRODUCTION_LEAK);
        assertTrue(cleaned.startsWith("嘿！我是 Coach。"),
                "expected cleaned text to start with the real Chinese reply; got: " + cleaned);
        assertFalse(cleaned.contains("User says:"), "scratchpad label 'User says:' leaked");
        assertFalse(cleaned.contains("Role:"), "scratchpad label 'Role:' leaked");
        assertFalse(cleaned.contains("Goal:"), "scratchpad label 'Goal:' leaked");
        assertFalse(cleaned.contains("Constraints:"), "scratchpad label 'Constraints:' leaked");
        assertFalse(cleaned.contains("Greeting:"), "scratchpad label 'Greeting:' leaked");
        assertFalse(cleaned.contains("Call to action:"), "scratchpad label 'Call to action:' leaked");
        assertFalse(cleaned.contains("Specifics needed:"), "scratchpad label 'Specifics needed:' leaked");
    }

    @Test
    void t2_legitimateOutputUnchanged() {
        String input = "嘿！我是 Coach。今天吃了什麼？\n\n* 雞胸 200g — 約 220 kcal\n* 白飯一碗 — 約 250 kcal";
        assertEquals(input, ResponseSanitizer.sanitize(input));
    }

    @Test
    void t3_legitimateOutputWithOneLabel() {
        String input = "建議：\n* Sleep: 8 hours\n* 多喝水";
        assertEquals(input, ResponseSanitizer.sanitize(input));
    }

    @Test
    void t4_emptyAndNullInputs() {
        assertEquals("", ResponseSanitizer.sanitize(null));
        assertEquals("", ResponseSanitizer.sanitize(""));
        assertEquals("", ResponseSanitizer.sanitize("   "));
    }

    @Test
    void t5_scratchpadOnly() {
        String input = ""
                + "*   User says: hi\n"
                + "*   Role: Coach\n"
                + "*   Goal: greet user politely\n"
                + "*   Constraints: short reply, no headers";
        assertEquals("", ResponseSanitizer.sanitize(input));
    }

    @Test
    void t6_boldMarkdownScratchpad() {
        String input = "**Role:** Coach\n**Goal:** help user\n\n你好！我是 Coach";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好！我是 Coach"),
                "expected start with '你好！我是 Coach'; got: " + cleaned);
        assertFalse(cleaned.contains("Role:"));
        assertFalse(cleaned.contains("Goal:"));
    }

    @Test
    void t7_inlineCjkAfterLastBullet() {
        String input = ""
                + "*   Role: Coach\n"
                + "*   Goal: greet\n"
                + "*   Specifics needed: Height, weight (muscle gain).嘿！我是 Coach";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("嘿！我是 Coach"),
                "expected start with '嘿！我是 Coach'; got: " + cleaned);
        assertFalse(cleaned.contains("Role:"));
        assertFalse(cleaned.contains("Goal:"));
        assertFalse(cleaned.contains("Specifics needed:"));
    }

    /** 2026-05-29 production leak: bullets use `.` not `:`, quoted draft, trailing repeat. */
    private static final String PROD_LEAK_2 = ""
            + "*   Greeting.\n"
            + "    *   Introduction.\n"
            + "    *   Call to action: Ask for necessary data (height, weight, age, gender, activity level, goal).\n"
            + "\n"
            + "    *   \"你好！我是 Coach。很高興見到你！為了幫你制定最適合的計畫，請告訴我你的：身高、體重、年齡、性別、平常的活動量，以及你的目標（例如：減脂、增肌或維持健康）。準備好後跟我說，我幫你算 BMR/TDEE 和營養配比！\"你好！我是 Coach。很高興見到你！\n"
            + "\n"
            + "為了幫你制定最適合的計畫，請先告訴我你的：**身高、體重、年齡、性別、平常的活動量**，以及你的**目標**（例如：減脂、增肌或維持健康）。\n"
            + "\n"
            + "準備好後跟我說，我立刻幫你計算 BMR/TDEE 和三大營養素的配比！  *   Greeting.\n"
            + "    *   Introduction.\n"
            + "    *   Call to action: Ask for necessary data (height, weight, age, gender, activity level, goal).";

    @Test
    void t8_periodEndingBulletsAndTrailing() {
        String cleaned = ResponseSanitizer.sanitize(PROD_LEAK_2);
        assertTrue(cleaned.startsWith("你好！我是 Coach"),
                "expected start with '你好！我是 Coach'; got: " + cleaned);
        assertFalse(cleaned.contains("Greeting."), "scratchpad 'Greeting.' leaked");
        assertFalse(cleaned.contains("Introduction."), "scratchpad 'Introduction.' leaked");
        assertFalse(cleaned.contains("Call to action"), "scratchpad 'Call to action' leaked");
        assertTrue(cleaned.contains("BMR/TDEE"), "real reply body lost");
        assertTrue(cleaned.endsWith("三大營養素的配比！"),
                "trailing scratchpad bullets should be stripped; got tail: "
                        + cleaned.substring(Math.max(0, cleaned.length() - 60)));
    }

    @Test
    void t9_quotedDraftStripped() {
        String input = ""
                + "* Role: Coach\n"
                + "* Plan: greet\n"
                + "\n"
                + "    *   \"draft 你好\"真實回覆從這裡開始\n"
                + "\n"
                + "第二段內容。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("真實回覆從這裡開始"),
                "expected start with '真實回覆從這裡開始'; got: " + cleaned);
        assertTrue(cleaned.contains("第二段內容"));
        assertFalse(cleaned.contains("draft"));
        assertFalse(cleaned.contains("Role:"));
        assertFalse(cleaned.contains("Plan:"));
    }

    @Test
    void t10_boldMarkdownStripped() {
        String input = "這是**重要**訊息和**強調**部分";
        assertEquals("這是重要訊息和強調部分", ResponseSanitizer.sanitize(input));
    }

    @Test
    void t11_underlineMarkdownStripped() {
        String input = "強調__文字__";
        assertEquals("強調文字", ResponseSanitizer.sanitize(input));
    }

    @Test
    void t12_legitimateBulletsWithUnknownLabelUntouched() {
        // Sleep / Tofu / Calorie are NOT in the scratchpad whitelist → both lines kept.
        String input = "建議：\n* Sleep: 8 hours\n* Tofu: 100g 80 kcal";
        assertEquals(input, ResponseSanitizer.sanitize(input));
    }

    @Test
    void t13_trailingBulletsAfterRealContent() {
        String input = ""
                + "* Role: coach\n"
                + "* Plan: greet\n"
                + "\n"
                + "你好世界！這是真實回覆。\n"
                + "\n"
                + "*   Greeting.\n"
                + "*   Call to action: ask data.";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好世界"), "got: " + cleaned);
        assertTrue(cleaned.endsWith("這是真實回覆。"),
                "trailing bullets should be stripped; got tail: " + cleaned);
        assertFalse(cleaned.contains("Greeting"));
        assertFalse(cleaned.contains("Call to action"));
    }

    @Test
    void t14_inlineTrailingBulletSplit() {
        // Real content + 2 spaces + trailing scratchpad bullet all on one line.
        String input = ""
                + "* Role: coach\n"
                + "* Plan: greet\n"
                + "\n"
                + "你好！這是回覆。  *   Greeting.\n"
                + "    *   Introduction.";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好！這是回覆。"),
                "got: " + cleaned);
        assertFalse(cleaned.contains("Greeting"));
        assertFalse(cleaned.contains("Introduction"));
    }

    @Test
    void t15_boldMarkdownRemovedFromRealReply() {
        // Even without scratchpad, bold markers must be stripped (Telegram default mode).
        String input = "你好！為你**量身打造**健身計畫";
        assertEquals("你好！為你量身打造健身計畫", ResponseSanitizer.sanitize(input));
    }

    @Test
    void t16_newProductionLeak_userInputRequestFor() {
        String input = ""
                + "*   User input: \"test\"\n"
                + "    *   Introduction as Coach.\n"
                + "*   Request for: Height, weight, age, gender, activity level, and goal (muscle gain/fat loss/maintenance).你好！我是 Coach。測試成功！\n"
                + "\n"
                + "準備好開始管理健康了嗎？請告訴我你的身高、體重、年齡、性別、平常的活動量以及目標（增肌 / 減脂 / 維持），我幫你計算 BMR、TDEE 並規劃三大營養素的配比！";
        String cleaned = ResponseSanitizer.sanitize(input);
        org.junit.jupiter.api.Assertions.assertTrue(cleaned.startsWith("你好！我是 Coach。測試成功！"),
                "expected start with '你好！我是 Coach。測試成功！'; got: " + cleaned);
        org.junit.jupiter.api.Assertions.assertFalse(cleaned.contains("User input"));
        org.junit.jupiter.api.Assertions.assertFalse(cleaned.contains("Introduction as Coach"));
        org.junit.jupiter.api.Assertions.assertFalse(cleaned.contains("Request for"));
        org.junit.jupiter.api.Assertions.assertTrue(cleaned.contains("BMR、TDEE"));
    }

    @Test
    void t17_multiWordLabels_firstWordMatch() {
        // first words "user", "request", "key", "important", "note" all in whitelist
        String input = "*   User profile considerations: foo\n"
                + "*   Request payload shape: bar\n"
                + "*   Important note: baz\n"
                + "\n你好！這是回覆。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好！"), "got: " + cleaned);
        assertFalse(cleaned.contains("User profile"));
        assertFalse(cleaned.contains("Request payload"));
        assertFalse(cleaned.contains("Important note"));
    }

    /** 2026-05-29 leak: Length / Ask for / Explain why bullets. */
    @Test
    void t18_v4ProductionLeak_lengthAskExplain() {
        String input = ""
                + "*   Length: Short (< 150 words).\n"
                + "    *   Ask for: height, weight, age, gender, activity level, and goal (muscle gain/fat loss/maintenance).\n"
                + "*   Explain why: To calculate BMR/TDEE and macro distribution.\n"
                + "\n"
                + "你好！我是 Coach。請告訴我你的身高體重年齡。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好！我是 Coach"),
                "expected start with Chinese reply; got: " + cleaned);
        assertFalse(cleaned.contains("Length"), "scratchpad 'Length' leaked");
        assertFalse(cleaned.contains("Ask for"), "scratchpad 'Ask for' leaked");
        assertFalse(cleaned.contains("Explain why"), "scratchpad 'Explain why' leaked");
    }

    /** Model wraps label in italic: `*   *Self-Correction:*` */
    @Test
    void t19_italicWrappedBulletLabel() {
        String input = ""
                + "*   *Self-Correction:* The user is testing.\n"
                + "    *   *Response Strategy:* Keep it brief.\n"
                + "\n"
                + "你好，這是真實回覆。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好"), "got: " + cleaned);
        assertFalse(cleaned.contains("Self-Correction"));
        assertFalse(cleaned.contains("Response Strategy"));
    }

    /** Thought-stream prose lines (no bullet, no `:`) must also be dropped in aggressive mode. */
    @Test
    void t20_englishProseDroppedInAggressiveMode() {
        String input = ""
                + "*   Role: Coach\n"
                + "*   Goal: greet\n"
                + "\n"
                + "Wait, looking at this — the user is asking why.\n"
                + "Crucial point: They want the prefix removed.\n"
                + "However, I should stay in character.\n"
                + "\n"
                + "你好！我是 Coach。為你制定計畫。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好"), "got: " + cleaned);
        assertFalse(cleaned.contains("Wait"));
        assertFalse(cleaned.contains("Crucial"));
        assertFalse(cleaned.contains("However"));
    }

    /** 2026-05-30 production leak: parenthetical content blocks old regex. */
    @Test
    void t21_v5ProductionLeak_maintainPersona() {
        String input = ""
                + "*   Maintain the persona (warm, direct, like a gym coach).我是 Coach，你的專屬健康教練！\n"
                + "\n"
                + "*   Maintain the persona (warm, direct, like a gym coach)";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("我是 Coach，你的專屬健康教練！"),
                "expected start with Chinese reply; got: " + cleaned);
        assertFalse(cleaned.contains("Maintain"), "scratchpad 'Maintain' leaked");
        assertFalse(cleaned.contains("persona"), "'persona' leaked");
    }

    /** Structural prefix detection: words not in whitelist, caught by position. */
    @Test
    void t22_structuralPrefixDetection_nonWhitelistWords() {
        String input = ""
                + "*   Capture user sentiment.\n"
                + "*   Formulate suitable welcome.\n"
                + "\n"
                + "你好！Coach 在這裡。";
        String cleaned = ResponseSanitizer.sanitize(input);
        assertTrue(cleaned.startsWith("你好！"), "got: " + cleaned);
        assertFalse(cleaned.contains("Capture"), "prefix bullet 'Capture' leaked");
        assertFalse(cleaned.contains("Formulate"), "prefix bullet 'Formulate' leaked");
    }
}
