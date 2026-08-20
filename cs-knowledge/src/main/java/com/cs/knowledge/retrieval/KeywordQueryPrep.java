package com.cs.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 关键词查询预处理：规范化与切词。过滤、打分、截断均在 PostgreSQL 完成。
 */
public final class KeywordQueryPrep {

    static final char TOKEN_SEP = 0x1F;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s，。？?！!、,\\.]+");

    private KeywordQueryPrep() {
    }

    public static Prepared prepare(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        List<String> tokens = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(normalized)) {
            if (token.length() < 2) {
                continue;
            }
            tokens.add(token.replace(String.valueOf(TOKEN_SEP), ""));
        }
        return new Prepared(normalized, String.join(String.valueOf(TOKEN_SEP), tokens));
    }

    public record Prepared(String query, String tokens) {
    }
}
