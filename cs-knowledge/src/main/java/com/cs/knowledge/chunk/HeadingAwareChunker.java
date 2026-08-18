package com.cs.knowledge.chunk;

import com.cs.knowledge.config.KnowledgeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题感知分块：优先按 Markdown 标题 / FAQ Q-A 切 section，超长再按句号递归切（带 overlap）。
 */
@Service
@RequiredArgsConstructor
public class HeadingAwareChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern QA_SPLIT = Pattern.compile("(?=^Q[:：])", Pattern.MULTILINE);
    private static final Pattern QA_HINT = Pattern.compile("(?m)^Q[:：]");

    private final KnowledgeProperties properties;

    public List<ChunkDraft> split(String title, String sourceType, String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            return List.of();
        }
        int size = Math.max(200, properties.getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlap(), size / 2));

        List<Section> sections;
        if ("FAQ".equalsIgnoreCase(sourceType) || QA_HINT.matcher(body).find()) {
            sections = splitFaq(title, body);
        } else {
            sections = splitByHeading(title, body);
        }

        List<ChunkDraft> out = new ArrayList<>();
        int ordinal = 0;
        for (Section section : sections) {
            for (String piece : splitOverflow(section.text(), size, overlap)) {
                if (piece.isBlank()) {
                    continue;
                }
                out.add(new ChunkDraft(ordinal++, section.heading(), piece.trim()));
            }
        }
        return out;
    }

    private List<Section> splitFaq(String title, String body) {
        String[] parts = QA_SPLIT.split(body);
        List<Section> sections = new ArrayList<>();
        int i = 0;
        for (String part : parts) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            String heading = title != null ? title : "FAQ";
            if (QA_HINT.matcher(text).find()) {
                String first = text.lines().findFirst().orElse("").replaceFirst("^Q[:：]\\s*", "");
                if (!first.isBlank()) {
                    heading = heading + " > " + trimHeading(first);
                }
            } else if (i == 0 && !text.startsWith("Q")) {
                heading = title != null ? title : "FAQ";
            }
            sections.add(new Section(heading, text));
            i++;
        }
        if (sections.isEmpty()) {
            sections.add(new Section(title, body));
        }
        return sections;
    }

    private List<Section> splitByHeading(String title, String body) {
        String[] lines = body.split("\n", -1);
        List<Section> sections = new ArrayList<>();
        String currentHeading = title != null ? title : "";
        StringBuilder buf = new StringBuilder();
        boolean sawHeading = false;
        for (String line : lines) {
            Matcher m = HEADING.matcher(line.trim());
            if (m.matches()) {
                flush(sections, currentHeading, buf);
                String h = m.group(2).trim();
                currentHeading = (title == null || title.isBlank()) ? h : title + " > " + h;
                sawHeading = true;
                continue;
            }
            if (!buf.isEmpty()) {
                buf.append('\n');
            }
            buf.append(line);
        }
        flush(sections, currentHeading, buf);
        if (sections.isEmpty()) {
            sections.add(new Section(title, body));
        } else if (!sawHeading && sections.size() == 1) {
            sections.set(0, new Section(title, body));
        }
        return sections;
    }

    private static void flush(List<Section> sections, String heading, StringBuilder buf) {
        String text = buf.toString().trim();
        buf.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new Section(heading, text));
        }
    }

    private List<String> splitOverflow(String text, int size, int overlap) {
        if (text.length() <= size) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int cut = lastPunct(text, start, end);
                if (cut > start + size / 2) {
                    end = cut;
                }
            }
            pieces.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }

    private static int lastPunct(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == ';' || c == '；') {
                return i + 1;
            }
        }
        return end;
    }

    private static String trimHeading(String s) {
        String t = s.length() > 80 ? s.substring(0, 80) + "…" : s;
        return t.replace('\n', ' ').trim();
    }

    public record ChunkDraft(int ordinal, String heading, String content) {
        public int tokenCount() {
            return content == null ? 0 : content.length();
        }
    }

    private record Section(String heading, String text) {}
}
