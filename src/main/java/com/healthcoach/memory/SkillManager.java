package com.healthcoach.memory;

import com.healthcoach.model.SkillSummary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Manages skill knowledge modules stored as SKILL.md files under dataDir/skills/. */
public class SkillManager {

    private final Path skillsDir;

    /** Create a manager rooted at dataDir/skills, creating directories as needed. */
    public SkillManager(Path dataDir) {
        this.skillsDir = dataDir.resolve("skills");
        try {
            Files.createDirectories(this.skillsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create skills directory: " + this.skillsDir, e);
        }
    }

    /** List all skills (subdirectories containing SKILL.md), sorted by name. */
    public List<SkillSummary> listSkills() {
        List<SkillSummary> result = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            return result;
        }
        try (Stream<Path> entries = Files.list(skillsDir)) {
            List<Path> dirs = new ArrayList<>();
            entries.filter(Files::isDirectory).forEach(dirs::add);
            for (Path dir : dirs) {
                Path md = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) {
                    continue;
                }
                String content = Files.readString(md, StandardCharsets.UTF_8);
                SkillSummary summary = parseFrontmatter(content, dir.getFileName().toString());
                result.add(summary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list skills in " + skillsDir, e);
        }
        result.sort(Comparator.comparing(s -> s.name));
        return result;
    }

    /** Read the full text of skills/{skillName}/SKILL.md, throwing if missing. */
    public String loadSkill(String skillName) {
        Path md = skillsDir.resolve(skillName).resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }
        try {
            return Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill: " + skillName, e);
        }
    }

    /** Patch a skill via append or replace; returns false if action invalid, skill missing, or replace target absent. */
    public boolean patchSkill(String skillName, String action, String content) {
        Path md = skillsDir.resolve(skillName).resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return false;
        }
        try {
            String original = Files.readString(md, StandardCharsets.UTF_8);
            String updated;
            if ("append".equals(action)) {
                updated = original + "\n" + (content == null ? "" : content);
            } else if ("replace".equals(action)) {
                if (content == null) {
                    return false;
                }
                int sep = content.indexOf("|||");
                if (sep < 0) {
                    return false;
                }
                String oldText = content.substring(0, sep);
                String newText = content.substring(sep + 3);
                if (!original.contains(oldText)) {
                    return false;
                }
                updated = original.replace(oldText, newText);
            } else {
                return false;
            }
            Files.writeString(md, updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to patch skill: " + skillName, e);
        }
    }

    /** Return a human-readable index of all available skills, or a placeholder when empty. */
    public String getSkillsIndexText() {
        List<SkillSummary> skills = listSkills();
        if (skills.isEmpty()) {
            return "可用的知識模組：（無）";
        }
        StringBuilder sb = new StringBuilder("可用的知識模組：");
        for (SkillSummary s : skills) {
            sb.append("\n- ").append(s.name).append(": ").append(s.description);
        }
        return sb.toString();
    }

    /** Parse YAML frontmatter (between leading --- lines) for name and description. */
    private static SkillSummary parseFrontmatter(String content, String fallbackName) {
        String name = fallbackName;
        String description = "";
        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length > 0 && "---".equals(lines[0].trim())) {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if ("---".equals(line.trim())) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("name".equals(key)) {
                    name = value;
                } else if ("description".equals(key)) {
                    description = value;
                }
            }
        } else {
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    description = line.trim();
                    break;
                }
            }
        }
        return new SkillSummary(name, description);
    }
}
