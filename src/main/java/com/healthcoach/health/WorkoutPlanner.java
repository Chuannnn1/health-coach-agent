package com.healthcoach.health;

/** Generates weekly workout plans as markdown based on goal and training frequency. */
public class WorkoutPlanner {

    /** Generates a weekly training plan as markdown. */
    public static String generateWeeklyPlan(String goal, int daysPerWeek) {
        if (daysPerWeek != 3 && daysPerWeek != 4) {
            return "建議每週訓練 3-4 天，請選擇 3 或 4。";
        }

        String goalNote = goalNote(goal);
        StringBuilder sb = new StringBuilder();

        if (daysPerWeek == 3) {
            sb.append("# 一週訓練計畫（3 天 Push/Pull/Legs）\n\n");
            sb.append("## Day A — Push（胸/肩/三頭）\n");
            sb.append("- 槓鈴臥推 4×8\n");
            sb.append("- 啞鈴肩推 3×10\n");
            sb.append("- 三頭繩索下壓 3×12\n\n");
            sb.append("## Day B — Pull（背/二頭）\n");
            sb.append("- 引體向上 3×max\n");
            sb.append("- 槓鈴划船 4×8\n");
            sb.append("- 啞鈴彎舉 3×12\n\n");
            sb.append("## Day C — Legs（腿）\n");
            sb.append("- 槓鈴深蹲 4×8\n");
            sb.append("- 腿推機 3×12\n");
            sb.append("- 腿彎舉 3×12\n");
        } else {
            sb.append("# 一週訓練計畫（4 天 Upper/Lower）\n\n");
            sb.append("## Day A — Upper（力量）\n");
            sb.append("- 臥推 4×8\n");
            sb.append("- 划船 4×8\n");
            sb.append("- 肩推 3×10\n");
            sb.append("- 彎舉 3×12\n");
            sb.append("- 三頭 3×12\n\n");
            sb.append("## Day B — Lower（力量）\n");
            sb.append("- 深蹲 4×8\n");
            sb.append("- 硬舉 3×6\n");
            sb.append("- 腿推 3×12\n");
            sb.append("- 小腿 4×15\n\n");
            sb.append("## Day C — Upper（量訓）\n");
            sb.append("- 啞鈴臥推 3×12\n");
            sb.append("- 引體 3×max\n");
            sb.append("- 側平舉 3×15\n");
            sb.append("- 面拉 3×15\n\n");
            sb.append("## Day D — Lower（量訓）\n");
            sb.append("- 保加利亞分腿蹲 3×10\n");
            sb.append("- 羅馬尼亞硬舉 3×10\n");
            sb.append("- 腿伸展 3×15\n");
            sb.append("- 腿彎舉 3×15\n");
        }

        if (!goalNote.isEmpty()) {
            sb.append("\n").append(goalNote);
        }

        return sb.toString();
    }

    private static String goalNote(String goal) {
        if (goal == null) return "";
        switch (goal) {
            case "muscle_gain": return "增肌建議：每組接近力竭，組間休息 90-120 秒";
            case "fat_loss":    return "減脂建議：組間休息 60-90 秒，可加入超級組節省時間";
            case "maintain":    return "維持建議：保持目前強度，不需要刻意加重";
            default:            return "";
        }
    }
}
