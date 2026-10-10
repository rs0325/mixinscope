package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.core.Util;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Tiny v2 mapping; descriptors are in the header's first namespace. */
public final class TinyMappings {
    private final Map<String, String> classes = new HashMap<>(), methods = new HashMap<>(), fields = new HashMap<>();
    public final String hash, from, to;
    public TinyMappings(Path file, String from, String to, long limit) throws IOException {
        this.from = from; this.to = to;
        if (Files.size(file) > limit) throw new IOException("Mapping file size limit exceeded");
        byte[] bytes = Files.readAllBytes(file); hash = Util.hash(bytes);
        List<String> lines = Arrays.asList(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n"));
        if (lines.isEmpty()) throw new IOException("Empty mappings");
        String[] header = lines.getFirst().stripTrailing().split("\t", -1);
        if (header.length < 5 || !header[0].equals("tiny") || !header[1].equals("2") || !header[2].equals("0")) throw new IOException("Only Tiny v2.0 mappings are supported");
        List<String> namespaces = Arrays.asList(header).subList(3, header.length);
        int source = namespaces.indexOf(from), target = namespaces.indexOf(to);
        if (source < 0 || target < 0 || new HashSet<>(namespaces).size() != namespaces.size()) throw new IOException("Missing or duplicated mapping namespace");
        Map<String, String> firstToSource = new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        boolean escaped = lines.stream().anyMatch(l -> l.stripTrailing().equals("\tescaped-names"));
        for (String raw : lines.subList(1, lines.size())) {
            String[] row = raw.replace("\r", "").split("\t", -1);
            if (escaped) for (int i = 0; i < row.length; i++) row[i] = unescape(row[i]);
            rows.add(row);
            if (row.length > 0 && row[0].equals("c")) {
                if (row.length < 1 + namespaces.size()) throw new IOException("Truncated class mapping");
                String first = row[1], src = name(row, 1 + source, first), dst = name(row, 1 + target, first);
                putUnique(classes, src, dst); putUnique(firstToSource, first, src);
            }
        }
        String owner = null;
        for (String[] row : rows) {
            if (row.length > 0 && row[0].equals("c")) owner = name(row, 1 + source, row[1]);
            else if (row.length > 1 && row[0].isEmpty() && (row[1].equals("m") || row[1].equals("f"))) {
                if (owner == null || row.length < 3 + namespaces.size()) throw new IOException("Truncated or unowned method mapping");
                String desc = descriptor(row[2], firstToSource);
                String src = name(row, 3 + source, row[3]), dst = name(row, 3 + target, row[3]);
                putUnique(row[1].equals("m") ? methods : fields, owner + "\u0000" + src + desc, dst);
            }
        }
    }
    private static void putUnique(Map<String, String> map, String key, String value) throws IOException {
        String prior = map.putIfAbsent(key, value);
        if (prior != null && !prior.equals(value)) throw new IOException("Ambiguous mappings: " + key);
    }
    private static String name(String[] row, int i, String fallback) { return row[i].isEmpty() ? fallback : row[i]; }
    private static String unescape(String s) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') out.append(c);
            else {
                if (++i == s.length()) throw new IOException("Invalid Tiny escape");
                out.append(switch (s.charAt(i)) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '0' -> '\0'; case '\\' -> '\\'; default -> throw new IOException("Invalid Tiny escape"); });
            }
        }
        return out.toString();
    }
    public String owner(String source) { return classes.getOrDefault(source, source); }
    public String method(String owner, String name, String desc) { return methods.getOrDefault(owner + "\u0000" + name + desc, name); }
    public String field(String owner, String name, String desc) { return fields.getOrDefault(owner + "\u0000" + name + desc, name); }
    public String descriptor(String descriptor) { return descriptor(descriptor, classes); }
    private static String descriptor(String descriptor, Map<String, String> classes) {
        Matcher matcher = Pattern.compile("L([^;]+);").matcher(descriptor);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement("L" + classes.getOrDefault(matcher.group(1), matcher.group(1)) + ";"));
        matcher.appendTail(result); return result.toString();
    }
}
