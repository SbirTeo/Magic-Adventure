package com.teolo.magixproxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.yml and messages.yml on the proxy always carry every key of the jar, like util/ConfigAlign
 * does for the Paper plugins (which this Velocity plugin cannot use).
 *
 * When the file on the server misses a key of the jar, it is rewritten from the jar's text (keys
 * in the jar's order, with the jar's comments) keeping every value chosen on the server. Keys the
 * jar no longer has are dropped, as dead lines. Before writing, the result is read back: if any
 * value of the server would change, or a key would be missing, nothing is written. A copy of the
 * old file is kept next to it ({@code .bak-<date>}).
 */
final class FileAlign {

    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(.*)$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private FileAlign() {
    }

    static void align(Path dir, String name, Logger log) {
        Path file = dir.resolve(name);
        try (InputStream in = FileAlign.class.getResourceAsStream("/" + name)) {
            if (in == null || !Files.isRegularFile(file)) {
                return;
            }
            String jarText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> jar = flat(new Yaml().load(jarText));
            Map<String, Object> live = flat(new Yaml().load(Files.readString(file, StandardCharsets.UTF_8)));
            List<String> missing = new ArrayList<>();
            for (String key : jar.keySet()) {
                if (!live.containsKey(key)) missing.add(key);
            }
            if (missing.isEmpty()) {
                return;
            }
            String merged = merge(jarText, jar, live);
            if (merged == null) {
                log.warn("MagixProxy: {} non allineato (un valore del server non si può riportare): file non toccato.", name);
                return;
            }
            Map<String, Object> check = flat(new Yaml().load(merged));
            for (Map.Entry<String, Object> e : jar.entrySet()) {
                Object expected = live.containsKey(e.getKey()) ? live.get(e.getKey()) : e.getValue();
                if (!Objects.equals(String.valueOf(expected), String.valueOf(check.get(e.getKey())))) {
                    log.warn("MagixProxy: {} non allineato ({} verrebbe cambiata): file non toccato.", name, e.getKey());
                    return;
                }
            }
            Files.copy(file, file.resolveSibling(name + ".bak-" + LocalDateTime.now().format(STAMP)));
            Files.writeString(file, merged, StandardCharsets.UTF_8);
            List<String> dropped = new ArrayList<>();
            for (String key : live.keySet()) {
                if (!jar.containsKey(key)) dropped.add(key);
            }
            log.info("MagixProxy: {} allineato al jar: aggiunte {}{}.", name, missing,
                    dropped.isEmpty() ? "" : ", tolte (non esistono più) " + dropped);
        } catch (Exception e) {
            log.warn("MagixProxy: {} non allineato ({}): file non toccato.", name, e.toString());
        }
    }

    /** The jar's text with the server's values in place of the jar's ones; null if one cannot be written. */
    private static String merge(String jarText, Map<String, Object> jar, Map<String, Object> live) {
        String[] lines = jarText.split("\n", -1);
        Deque<Object[]> stack = new ArrayDeque<>(); // {indent, key}
        StringBuilder out = new StringBuilder(jarText.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = KEY_LINE.matcher(line);
            if (line.isBlank() || line.stripLeading().startsWith("#") || !m.matches()) {
                out.append(line);
            } else {
                int indent = m.group(1).length();
                while (!stack.isEmpty() && (int) stack.peek()[0] >= indent) stack.pop();
                StringBuilder path = new StringBuilder();
                stack.descendingIterator().forEachRemaining(p -> path.append(p[1]).append('.'));
                path.append(m.group(2));
                String key = path.toString();
                if (!jar.containsKey(key)) {
                    stack.push(new Object[]{indent, m.group(2)}); // a section
                    out.append(line);
                } else if (live.containsKey(key) && !Objects.equals(live.get(key), jar.get(key))) {
                    Object value = live.get(key);
                    if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                        return null;
                    }
                    out.append(m.group(1)).append(m.group(2)).append(": ").append(render(value))
                            .append(trailingComment(m.group(3)));
                } else {
                    out.append(line);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String render(Object value) {
        if (!(value instanceof String s)) {
            return String.valueOf(value);
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    /** The comment after a value ("   # ..."), kept when the value is replaced. */
    private static String trailingComment(String rest) {
        String r = rest.strip();
        int from = 0;
        if (r.startsWith("\"") || r.startsWith("'")) {
            char q = r.charAt(0);
            int j = 1;
            while (j < r.length()) {
                char c = r.charAt(j);
                if (q == '"' && c == '\\') { j += 2; continue; }
                if (c == q) break;
                j++;
            }
            from = Math.min(j + 1, r.length());
        }
        int hash = r.indexOf(" #", from);
        if (hash < 0) {
            return "";
        }
        int at = rest.indexOf(r.substring(hash));
        // keep the original spacing before the comment
        int start = at;
        while (start > 0 && rest.charAt(start - 1) == ' ') start--;
        return rest.substring(start);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flat(Object root) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (root instanceof Map<?, ?> m) flatten((Map<String, Object>) m, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> child) {
                flatten((Map<String, Object>) child, path, out);
            } else {
                out.put(path, e.getValue());
            }
        }
    }
}
