package com.cs.knowledge.parse;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析：Markdown/TXT 按 UTF-8 读取以保留标题；PDF/Word/HTML 走 Apache Tika。
 * 扫描件无文字层时返回空串，由调用方标 FAILED（本轮不做 OCR）。
 */
@Slf4j
@Service
public class DocumentParseService {

    private static final Set<String> PLAIN_EXT = Set.of("md", "markdown", "txt", "text");

    public String parseFile(Path path, String fileName) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        String ext = extension(fileName != null ? fileName : path.getFileName().toString());
        try {
            if (PLAIN_EXT.contains(ext)) {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            }
            return parseWithTika(path);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文档解析失败: " + e.getMessage(), e);
        }
    }

    public String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String parseWithTika(Path path) throws Exception {
        Parser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, handler, metadata, context);
        }
        String text = handler.toString();
        log.debug("Tika parsed {}: chars={}", path.getFileName(), text != null ? text.length() : 0);
        return normalizeText(text);
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
