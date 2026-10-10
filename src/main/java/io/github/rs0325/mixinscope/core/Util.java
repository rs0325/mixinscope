package io.github.rs0325.mixinscope.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.*;

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
    public static String preview(String value,int maximum){return value.length()<=maximum?value:value.substring(0,maximum)+" [truncated; see inventory]";}
    public static String previewJson(Object value,int maximum) {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try{JSON.writeValue(new BoundedOutputStream(bytes,maximum),value);return bytes.toString(StandardCharsets.UTF_8);}
        catch(IOException error){return "[metadata preview exceeds "+maximum+" bytes; see complete inventory]";}
    }
    /** '*' is the only metacharacter. Greedy matching avoids regex backtracking and checks the scan budget. */
    public static boolean matchesGlob(String pattern,String value,Runnable checkBudget) {
        int p=0,v=0,star=-1,retry=0,steps=0;checkBudget.run();
        while(v<value.length()) {
            if((++steps&1023)==0)checkBudget.run();
            if(p<pattern.length()&&pattern.charAt(p)!='*'&&pattern.charAt(p)==value.charAt(v)){p++;v++;}
            else if(p<pattern.length()&&pattern.charAt(p)=='*'){star=p++;retry=v;}
            else if(star>=0){p=star+1;v=++retry;}
            else return false;
        }
        while(p<pattern.length()&&pattern.charAt(p)=='*'){p++;if((++steps&1023)==0)checkBudget.run();}
        return p==pattern.length();
    }
    public static Set<String> sides(String side) {
        return new TreeSet<>(switch (side) { case "client" -> List.of("client"); case "server" -> List.of("server"); default -> List.of("client", "server"); });
    }
    public static Map<String, Object> json(byte[] bytes) throws java.io.IOException {
        JsonNode n = JSON.readTree(bytes);
        if (n == null || !n.isObject()) throw new java.io.IOException("Expected a JSON object");
        return JSON.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    public static byte[] readBytes(java.nio.file.Path path,long maximum,Runnable checkBudget)throws IOException {
        if(!java.nio.file.Files.isRegularFile(path))throw new IOException("Expected a regular file");
        if(java.nio.file.Files.size(path)>maximum)throw new IOException("File size limit exceeded");
        try(InputStream input=java.nio.file.Files.newInputStream(path);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];
            for(int count;(count=input.read(buffer))!=-1;) {
                checkBudget.run();
                if(count>maximum-output.size())throw new IOException("File size limit exceeded");
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }
}
