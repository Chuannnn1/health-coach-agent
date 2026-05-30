package com.healthcoach.bot;

import com.healthcoach.health.BmrCalculator;
import com.healthcoach.health.NutritionPlanner;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.model.MacroTarget;
import com.healthcoach.model.UserProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Step-by-step state machine that guides users through filling their health
 * profile via interactive prompts (instead of relying on LLM parsing).
 */
public class ProfileWizard {

    public enum Step {
        NAME, GENDER, HEIGHT, WEIGHT, AGE, ACTIVITY, ACTIVITY_ASSESS, GOAL, DONE
    }

    /** Response from wizard -- either ask a question (with optional choices) or show completion. */
    public record WizardResponse(String text, List<String> choices, boolean done) {
        public static WizardResponse ask(String text) {
            return new WizardResponse(text, List.of(), false);
        }
        public static WizardResponse askWithChoices(String text, List<String> choices) {
            return new WizardResponse(text, choices, false);
        }
        public static WizardResponse complete(String text) {
            return new WizardResponse(text, List.of(), true);
        }
    }

    private static class Session {
        Step step = Step.NAME;
        final UserProfile draft = new UserProfile();
    }

    private static final String ASSESS_PROMPT =
            "用戶正在設定健康檔案，他不確定自己的日常活動量等級。以下是他的描述：\n\n「%s」\n\n" +
            "請根據這段描述判斷活動量等級，只回覆以下其中一個關鍵字（不要回覆任何其他內容）：\n" +
            "sedentary — 久坐辦公，幾乎不運動\n" +
            "light — 每週運動 1-3 次或輕度日常活動\n" +
            "moderate — 每週運動 3-5 次\n" +
            "active — 每週運動 6-7 次或體力工作\n" +
            "very_active — 每天高強度訓練或重體力勞動";

    private final MemoryStore memoryStore;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private Function<String, String> assessor;

    public ProfileWizard(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void setAssessor(Function<String, String> assessor) {
        this.assessor = assessor;
    }

    /** Check if a user currently has an active wizard session. */
    public boolean isActive(String chatId) {
        return sessions.containsKey(chatId);
    }

    /** Start a new wizard session. Returns the first question. */
    public WizardResponse start(String chatId) {
        Session s = new Session();
        // Pre-fill from existing profile if available
        UserProfile existing = memoryStore.loadUserProfile();
        if (existing.name != null && !existing.name.isBlank()) {
            s.draft.name = existing.name;
            s.draft.heightCm = existing.heightCm;
            s.draft.weightKg = existing.weightKg;
            s.draft.age = existing.age;
            s.draft.gender = existing.gender;
            s.draft.activityLevel = existing.activityLevel;
            s.draft.goal = existing.goal;
        }
        sessions.put(chatId, s);
        return promptForStep(s);
    }

    /** Cancel an active session. */
    public void cancel(String chatId) {
        sessions.remove(chatId);
    }

    /** Handle user input (text message or callback button press). */
    public WizardResponse handle(String chatId, String input) {
        Session s = sessions.get(chatId);
        if (s == null) return WizardResponse.ask("沒有進行中的設定。用 /setup 開始。");

        if ("/cancel".equals(input.trim())) {
            sessions.remove(chatId);
            return WizardResponse.complete("已取消設定。");
        }

        try {
            return processInput(s, chatId, input.trim());
        } catch (NumberFormatException e) {
            return WizardResponse.ask("請輸入有效的數字。");
        }
    }

    // ------------------------------------------------------------------ //
    //  Input processing per step                                          //
    // ------------------------------------------------------------------ //

    private WizardResponse processInput(Session s, String chatId, String input) {
        switch (s.step) {
            case NAME: {
                s.draft.name = input;
                s.step = Step.GENDER;
                return promptForStep(s);
            }
            case GENDER: {
                String gender = normalizeGender(input);
                if (gender == null) {
                    return WizardResponse.askWithChoices("請選擇性別：", List.of("男", "女", "其他"));
                }
                s.draft.gender = gender;
                s.step = Step.HEIGHT;
                return promptForStep(s);
            }
            case HEIGHT: {
                double h = Double.parseDouble(input);
                if (h < 50 || h > 300) {
                    return WizardResponse.ask("身高範圍異常，請輸入合理數值（cm）：");
                }
                s.draft.heightCm = h;
                s.step = Step.WEIGHT;
                return promptForStep(s);
            }
            case WEIGHT: {
                double w = Double.parseDouble(input);
                if (w < 20 || w > 500) {
                    return WizardResponse.ask("體重範圍異常，請輸入合理數值（kg）：");
                }
                s.draft.weightKg = w;
                s.step = Step.AGE;
                return promptForStep(s);
            }
            case AGE: {
                int a = Integer.parseInt(input);
                if (a < 5 || a > 120) {
                    return WizardResponse.ask("年齡範圍異常，請輸入合理數值：");
                }
                s.draft.age = a;
                s.step = Step.ACTIVITY;
                return promptForStep(s);
            }
            case ACTIVITY: {
                if ("不確定".equals(input) || "unsure".equalsIgnoreCase(input)) {
                    s.step = Step.ACTIVITY_ASSESS;
                    return promptForStep(s);
                }
                String level = normalizeActivity(input);
                if (level == null) {
                    return promptForStep(s);
                }
                s.draft.activityLevel = level;
                s.step = Step.GOAL;
                return promptForStep(s);
            }
            case ACTIVITY_ASSESS: {
                String result = assessActivity(input);
                if (result == null) {
                    s.step = Step.ACTIVITY;
                    return WizardResponse.askWithChoices(
                            "抱歉，我沒辦法從描述判斷，請直接選擇：",
                            List.of("久坐", "輕度活動", "中度活動", "高度活動", "極高活動"));
                }
                s.draft.activityLevel = result;
                s.step = Step.GOAL;
                String display = "根據你的描述，我判斷你的活動量是「" + result + "」\n\n";
                WizardResponse next = promptForStep(s);
                return WizardResponse.askWithChoices(display + next.text(), next.choices());
            }
            case GOAL: {
                String goal = normalizeGoal(input);
                if (goal == null) {
                    return WizardResponse.askWithChoices("請選擇目標：",
                            List.of("增肌", "減脂", "維持"));
                }
                s.draft.goal = goal;
                s.step = Step.DONE;
                return finalize(s, chatId);
            }
            default:
                sessions.remove(chatId);
                return WizardResponse.complete("設定已完成。");
        }
    }

    // ------------------------------------------------------------------ //
    //  Prompts                                                            //
    // ------------------------------------------------------------------ //

    private WizardResponse promptForStep(Session s) {
        return switch (s.step) {
            case NAME -> {
                String hint = s.draft.name.isBlank() ? "" : "（目前：" + s.draft.name + "）";
                yield WizardResponse.ask("開始設定個人資料！隨時可用 /cancel 取消。\n\n請輸入你的名字" + hint + "：");
            }
            case GENDER -> WizardResponse.askWithChoices(
                    "請選擇性別：", List.of("男", "女", "其他"));
            case HEIGHT -> {
                String hint = s.draft.heightCm > 0 ? "（目前：" + (int) s.draft.heightCm + "）" : "";
                yield WizardResponse.ask("請輸入身高（cm）" + hint + "：");
            }
            case WEIGHT -> {
                String hint = s.draft.weightKg > 0 ? "（目前：" + (int) s.draft.weightKg + "）" : "";
                yield WizardResponse.ask("請輸入體重（kg）" + hint + "：");
            }
            case AGE -> {
                String hint = s.draft.age > 0 ? "（目前：" + s.draft.age + "）" : "";
                yield WizardResponse.ask("請輸入年齡" + hint + "：");
            }
            case ACTIVITY -> WizardResponse.askWithChoices(
                    "請選擇日常活動量：", activityChoices());
            case ACTIVITY_ASSESS -> WizardResponse.ask(
                    "請簡單描述你的日常作息和運動習慣，我來幫你判斷。\n" +
                    "例如：「平常上班久坐，每週去健身房 3 次做重訓」");
            case GOAL -> WizardResponse.askWithChoices(
                    "請選擇目標：", List.of("增肌", "減脂", "維持"));
            case DONE -> WizardResponse.complete("設定已完成。");
        };
    }

    // ------------------------------------------------------------------ //
    //  Finalize: BMR / TDEE / macros calculation + persist                //
    // ------------------------------------------------------------------ //

    private WizardResponse finalize(Session s, String chatId) {
        UserProfile p = s.draft;

        // Map Chinese display values to the English keys expected by BmrCalculator / NutritionPlanner
        String genderKey = toGenderKey(p.gender);       // "male" | "female"
        String activityKey = toActivityKey(p.activityLevel); // "sedentary" | "light" | ...
        String goalKey = toGoalKey(p.goal);              // "muscle_gain" | "fat_loss" | "maintain"

        int bmr = BmrCalculator.calculateBmr(p.weightKg, p.heightCm, p.age, genderKey);
        int tdee = BmrCalculator.calculateTdee(bmr, activityKey);
        int targetCalories = BmrCalculator.calculateTargetCalories(tdee, goalKey);
        MacroTarget macro = NutritionPlanner.calculateMacros(targetCalories, p.weightKg, goalKey);

        p.bmr = bmr;
        p.tdee = tdee;
        p.targetCalories = targetCalories;
        p.targetProteinG = macro.proteinG;
        p.targetCarbsG = macro.carbsG;
        p.targetFatG = macro.fatG;
        p.updatedAt = Instant.now().toString();

        memoryStore.saveUserProfile(p);
        sessions.remove(chatId);

        String summary = String.format(
                "設定完成！\n\n" +
                "姓名：%s\n" +
                "性別：%s  年齡：%d\n" +
                "身高：%.0f cm  體重：%.0f kg\n" +
                "活動量：%s  目標：%s\n\n" +
                "BMR：%d kcal\n" +
                "TDEE：%d kcal\n" +
                "目標熱量：%d kcal\n" +
                "蛋白質：%d g / 碳水：%d g / 脂肪：%d g\n\n" +
                "隨時可以用 /setup 重新設定，或直接跟我說要修改的部分。",
                p.name, p.gender, p.age, p.heightCm, p.weightKg,
                p.activityLevel, p.goal, bmr, tdee,
                targetCalories, macro.proteinG, macro.carbsG, macro.fatG
        );
        return WizardResponse.complete(summary);
    }

    // ------------------------------------------------------------------ //
    //  Normalizers: user input -> Chinese display value                   //
    // ------------------------------------------------------------------ //

    private String normalizeGender(String input) {
        return switch (input.toLowerCase()) {
            case "男", "male", "m" -> "男";
            case "女", "female", "f" -> "女";
            case "其他", "other" -> "其他";
            default -> null;
        };
    }

    private String normalizeActivity(String input) {
        String lower = input.toLowerCase().replace(" ", "");
        if (lower.contains("久坐") || lower.equals("sedentary") || lower.equals("1")) return "久坐";
        if (lower.contains("輕度") || lower.equals("light") || lower.equals("2")) return "輕度活動";
        if (lower.contains("中度") || lower.equals("moderate") || lower.equals("3")) return "中度活動";
        if ((lower.contains("高度") && !lower.contains("極")) || lower.equals("active") || lower.equals("4")) return "高度活動";
        if (lower.contains("極高") || lower.equals("veryactive") || lower.equals("very_active") || lower.equals("5")) return "極高活動";
        return null;
    }

    private String normalizeGoal(String input) {
        String lower = input.toLowerCase();
        if (lower.contains("增肌") || lower.contains("bulk") || lower.contains("增重")) return "增肌";
        if (lower.contains("減脂") || lower.contains("cut") || lower.contains("減重") || lower.contains("瘦")) return "減脂";
        if (lower.contains("維持") || lower.contains("maintain") || lower.contains("保持")) return "維持";
        return null;
    }

    private List<String> activityChoices() {
        if (assessor != null) {
            return List.of("久坐", "輕度活動", "中度活動", "高度活動", "極高活動", "不確定");
        }
        return List.of("久坐", "輕度活動", "中度活動", "高度活動", "極高活動");
    }

    private String assessActivity(String userDescription) {
        if (assessor == null) return null;
        try {
            String response = assessor.apply(String.format(ASSESS_PROMPT, userDescription));
            if (response == null) return null;
            String lower = response.strip().toLowerCase();
            if (lower.contains("very_active")) return "極高活動";
            if (lower.contains("active") && !lower.contains("light")) return "高度活動";
            if (lower.contains("moderate")) return "中度活動";
            if (lower.contains("light")) return "輕度活動";
            if (lower.contains("sedentary")) return "久坐";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Mappers: Chinese display value -> English key for calculators      //
    // ------------------------------------------------------------------ //

    private static String toGenderKey(String gender) {
        return switch (gender) {
            case "男" -> "male";
            case "女" -> "female";
            // BmrCalculator only accepts male/female; default to male for "其他"
            default -> "male";
        };
    }

    private static String toActivityKey(String activityLevel) {
        return switch (activityLevel) {
            case "久坐" -> "sedentary";
            case "輕度活動" -> "light";
            case "中度活動" -> "moderate";
            case "高度活動" -> "active";
            case "極高活動" -> "very_active";
            default -> "moderate";
        };
    }

    private static String toGoalKey(String goal) {
        return switch (goal) {
            case "增肌" -> "muscle_gain";
            case "減脂" -> "fat_loss";
            case "維持" -> "maintain";
            default -> "maintain";
        };
    }
}
