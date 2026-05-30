package com.healthcoach.agent;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips local-model scratchpad leaks and unrendered markdown before the
 * reply reaches Telegram.
 *
 * <p>Detection uses two complementary paths:
 * <ul>
 *   <li><b>Whitelist</b> — bullet first-word lookup against known planning tokens.</li>
 *   <li><b>Structural</b> — count English-starting bullets in the prefix/suffix
 *       zones (before/after the first/last non-bullet CJK line). Two or more
 *       triggers aggressive mode regardless of specific words.</li>
 * </ul>
 *
 * <p>In aggressive mode every English-starting bullet, quoted-draft line, and
 * pure-English prose line is stripped. The Coach replies in Traditional Chinese,
 * so English-only lines in a scratchpad context are always leaks.
 */
public final class ResponseSanitizer {

    private static final Set<String> SCRATCHPAD_FIRST_WORDS = Set.of(
            "user", "context", "role", "goal", "goals",
            "constraint", "constraints", "tone", "style", "output",
            "purpose", "greeting", "introduction", "intro",
            "call", "specifics", "plan", "approach", "step", "steps",
            "consideration", "considerations", "thought", "thinking",
            "analysis", "objective", "format", "directive",
            "response", "instruction", "instructions",
            "task", "summary", "draft", "reply", "answer",
            "rationale", "reasoning", "intent",
            "request", "input", "expected", "desired", "language", "tools",
            "scenario", "situation", "background", "consider",
            "note", "notes", "remember", "important", "key", "main",
            "length", "ask", "explain",
            "wait", "crucial", "looking", "actually",
            "self", "refined", "strategy",
            "maintain", "provide", "keep", "ensure", "follow",
            "avoid", "address", "acknowledge"
    );

    /**
     * Captures the first English word from a bullet label. The permissive
     * {@code .*?} reaches the nearest {@code :} or {@code .} regardless of
     * parenthetical content, quotes, etc.
     */
    private static final Pattern BULLET_LABEL = Pattern.compile(
            "^\\s*\\*{1,2}\\s*\\*?([A-Z][A-Za-z]+)\\b.*?[:.]"
    );

    /** Any bullet starting with an uppercase English letter. */
    private static final Pattern ENG_BULLET_START = Pattern.compile(
            "^\\s*\\*{1,2}\\s*\\*?[A-Z]"
    );

    /** {@code * "<draft>"<real reply tail>}. Group 1 = post-quote tail. */
    private static final Pattern QUOTED_DRAFT = Pattern.compile(
            "^\\s*\\*+\\s+\"[^\"]*\"(.*)$"
    );

    /** Real content + 2+ spaces + trailing bullet on same line. */
    private static final Pattern INLINE_TRAILING_BULLET = Pattern.compile(
            "(\\S)(\\s{2,})(\\*{1,2}\\s+[A-Z][^\\n]{0,60}[:.])"
    );

    private static final Pattern BOLD_MD = Pattern.compile("\\*\\*([^*\\n]+?)\\*\\*");
    private static final Pattern UNDERLINE_MD = Pattern.compile("__([^_\\n]+?)__");

    private static final char CJK_START = '一';
    private static final char CJK_END = '鿿';

    private static final int BULLET_THRESHOLD = 2;

    private ResponseSanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty() || raw.trim().isEmpty()) return "";

        String preprocessed = INLINE_TRAILING_BULLET.matcher(raw).replaceAll("$1\n$3");
        String[] lines = preprocessed.split("\n", -1);

        int whitelistHits = 0;
        for (String line : lines) {
            if (matchesWhitelist(line) || QUOTED_DRAFT.matcher(line).matches()) {
                whitelistHits++;
            }
        }

        boolean aggressive = whitelistHits >= BULLET_THRESHOLD
                || countPrefixBullets(lines) >= BULLET_THRESHOLD;

        String working;
        if (!aggressive) {
            working = raw;
        } else {
            StringBuilder out = new StringBuilder(raw.length());
            boolean emittedContent = false;
            for (String line : lines) {
                Matcher qm = QUOTED_DRAFT.matcher(line);
                if (qm.matches()) {
                    String tail = qm.group(1).trim();
                    if (!tail.isEmpty()) {
                        out.append(tail).append('\n');
                        emittedContent = true;
                    }
                    continue;
                }
                if (ENG_BULLET_START.matcher(line).find()) {
                    int cjk = firstCjkIndex(line);
                    if (cjk >= 0) {
                        out.append(line, cjk, line.length()).append('\n');
                        emittedContent = true;
                    }
                    continue;
                }
                if (!hasCjk(line) && hasLatinLetter(line)) continue;

                if (line.trim().isEmpty() && !emittedContent) continue;
                out.append(line).append('\n');
                if (!line.trim().isEmpty()) emittedContent = true;
            }
            working = out.toString();
        }

        working = BOLD_MD.matcher(working).replaceAll("$1");
        working = UNDERLINE_MD.matcher(working).replaceAll("$1");

        return rtrim(working);
    }

    private static boolean matchesWhitelist(String line) {
        if (line == null || line.isEmpty()) return false;
        Matcher m = BULLET_LABEL.matcher(line);
        if (!m.find()) return false;
        return SCRATCHPAD_FIRST_WORDS.contains(m.group(1).toLowerCase());
    }

    /** Count English bullets before the first non-bullet CJK line. */
    private static int countPrefixBullets(String[] lines) {
        int count = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (hasCjk(line) && !ENG_BULLET_START.matcher(line).find()) break;
            if (ENG_BULLET_START.matcher(line).find()) count++;
        }
        return count;
    }

    private static int firstCjkIndex(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) >= CJK_START && line.charAt(i) <= CJK_END) return i;
        }
        return -1;
    }

    private static boolean hasCjk(String line) {
        return firstCjkIndex(line) >= 0;
    }

    private static boolean hasLatinLetter(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    private static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
