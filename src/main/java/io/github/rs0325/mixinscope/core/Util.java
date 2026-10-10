package io.github.rs0325.mixinscope.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Util {
    private Util() {}
    public static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(4_000_000).maxNumberLength(1000).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String id(String... parts) { return hash(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)); }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    public static List<Object> list(Object value) {
        if (value == null) return List.of();
        return value instanceof List<?> l ? new ArrayList<>(l) : List.of(value);
    }
    public static List<String> strings(Object value) { return list(value).stream().filter(String.class::isInstance).map(Object::toString).toList(); }
    public static String str(Object value, String fallback) { return value instanceof String s ? s : fallback; }
    public static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    public static boolean bool(Object value, boolean fallback) { return value instanceof Boolean b ? b : fallback; }
    public static String owner(String value) { return value.replace('.', '/'); }
    public static Set<String> sides(String side) {
        return new TreeSet<>(switch (side) { case "client" -> List.of("client"); case "server" -> List.of("server"); default -> List.of("client", "server"); });
    }
    public static Map<String, Object> json(byte[] bytes) throws java.io.IOException {
        JsonNode n = JSON.readTree(bytes);
        if (n == null || !n.isObject()) throw new java.io.IOException("Expected a JSON object");
        return JSON.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
