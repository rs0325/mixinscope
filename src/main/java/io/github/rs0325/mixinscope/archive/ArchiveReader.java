package io.github.rs0325.mixinscope.archive;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;

/** Reads bytes only: no class loaders, services, native code, or plugin callbacks. */
public final class ArchiveReader {
    private final ScanOptions options;
    private final Report report;
    private final Map<String, Artifact> byHash = new TreeMap<>();
    private long total;
    private int entries;
    private final long deadline;
    public ArchiveReader(ScanOptions options, Report report) {
        this.options = options; this.report = report;
        deadline = System.nanoTime() + options.timeoutSeconds * 1_000_000_000L;
    }
    public void checkTime() throws IOException {
        options.checkBudget();
        if (System.nanoTime() > deadline) throw new IOException("Analysis time limit exceeded");
    }
    public List<Artifact> roots(List<Path> inputs) {
        List<Artifact> result = new ArrayList<>();
        for (Path input : inputs.stream().sorted().toList()) {
            try {
                if (Files.isSymbolicLink(input)) throw new IOException("Symbolic link input rejected");
                if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                    try (var stream = Files.list(input)) {
                        for (Path path : stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                            Artifact a = file(path, path.getFileName().toString());
                            if (a != null) result.add(a);
                        }
                    }
                } else {
                    Artifact a = file(input, input.getFileName().toString());
                    if (a != null) result.add(a);
                }
            } catch (IOException e) { report.diagnostic("INPUT_ERROR", input.getFileName().toString(), e.getMessage(), true); }
        }
        if (result.isEmpty()) report.diagnostic("NO_INPUT", "input", "No readable JAR inputs", true);
        return result.stream().distinct().toList();
    }
    public Artifact file(Path path, String origin) {
        try {
            checkTime();
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link input rejected");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular JAR file");
            try (InputStream in = Files.newInputStream(path)) { return read(bounded(in, options.maxArchive), origin, 0); }
        } catch (IOException | RuntimeException e) {
            report.diagnostic("ARCHIVE_ERROR", origin, safeMessage(e), true); return null;
        }
    }
    public Artifact nested(Artifact parent, String path, int depth) {
        String origin = parent.hash + "!/" + path;
        try {
            if (!safePath(path)) throw new IOException("Unsafe nested JAR path");
            if (depth > options.maxDepth) throw new IOException("Nested archive depth exceeded");
            byte[] bytes = parent.entries.get(path);
            if (bytes == null) throw new IOException("Declared nested JAR missing");
            Artifact child = read(bytes, origin, depth);
            if (child.hash.equals(parent.hash)) throw new IOException("Recursive archive rejected");
            return child;
        } catch (IOException | RuntimeException e) { report.diagnostic("NESTED_ERROR", origin, safeMessage(e), true); return null; }
    }
    public Artifact read(byte[] bytes, String origin, int depth) throws IOException {
        checkTime();
        if (bytes.length > options.maxArchive) throw new IOException("Archive size limit exceeded");
        String hash = Util.hash(bytes);
        if (byHash.containsKey(hash)) {
            Artifact prior = byHash.get(hash);
            if (!prior.origins.contains(origin)) prior.origins.add(origin);
            prior.nested &= depth > 0;
            return prior;
        }
        Artifact artifact = new Artifact(); artifact.hash = hash; artifact.size = bytes.length;
        artifact.origins.add(origin); artifact.nested = depth > 0;
        byHash.put(hash, artifact); report.artifacts.add(artifact);
        try (var channel = new SeekableInMemoryByteChannel(bytes);
             var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            Enumeration<ZipArchiveEntry> all = zip.getEntries();
            Set<String> names = new HashSet<>();
            while (all.hasMoreElements()) {
                checkTime();
                ZipArchiveEntry e = all.nextElement();
                if (++entries > options.maxEntries) throw new IOException("Archive entry count exceeded");
                if (!safePath(e.getName()) || e.isUnixSymlink()) throw new IOException("Unsafe ZIP entry: " + e.getName());
                if (!names.add(e.getName())) throw new IOException("Duplicate ZIP entry: " + e.getName());
                if (e.isDirectory()) continue;
                if (e.getSize() < 0 || e.getCompressedSize() < 0 || e.getSize() > options.maxEntry)
                    throw new IOException("Entry size unknown or over limit: " + e.getName());
                if (e.getSize() > Math.max(1, e.getCompressedSize()) * options.maxRatio)
                    throw new IOException("Compression ratio exceeded: " + e.getName());
                if (e.getSize() > options.maxTotal - total) throw new IOException("Total expanded bytes exceeded");
                if (!zip.canReadEntryData(e)) throw new IOException("Unsupported ZIP entry encoding");
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] value = bounded(in, options.maxEntry);
                    total += value.length;
                    if (total > options.maxTotal) throw new IOException("Total expanded bytes exceeded");
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(value);
                    if (e.getSize() != value.length || e.getCrc() != crc.getValue()) throw new IOException("ZIP size / CRC mismatch");
                    artifact.entries.put(e.getName(), value);
                }
            }
            selectMultiRelease(artifact);
        } catch (IOException | RuntimeException e) {
            artifact.partial = true;
            report.diagnostic("ARCHIVE_ERROR", hash, safeMessage(e), true);
            // Never treat a truncated inventory as a complete config discovery.
        }
        return artifact;
    }
    private void selectMultiRelease(Artifact artifact) throws IOException {
        byte[] manifest = artifact.entries.get("META-INF/MANIFEST.MF");
        boolean enabled = manifest != null && "true".equalsIgnoreCase(new Manifest(new ByteArrayInputStream(manifest))
                .getMainAttributes().getValue("Multi-Release"));
        Map<String, Integer> versions = new HashMap<>();
        Map<String, byte[]> overrides = new TreeMap<>();
        for (var e : artifact.entries.entrySet()) {
            if (!e.getKey().startsWith("META-INF/versions/")) continue;
            String[] parts = e.getKey().split("/", 4);
            if (parts.length != 4 || !parts[3].endsWith(".class")) continue;
            try {
                int version = Integer.parseInt(parts[2]);
                if (enabled && version >= 9 && version <= options.targetJava && version > versions.getOrDefault(parts[3], 0)) {
                    versions.put(parts[3], version); overrides.put(parts[3], e.getValue());
                }
            } catch (NumberFormatException ignored) { /* Not a versioned class entry. */ }
        }
        artifact.entries.putAll(overrides);
        if (!versions.isEmpty()) artifact.metadata.put("multiReleaseClasses", new TreeMap<>(versions));
    }
    public byte[] bounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
            checkTime();
            if ((long) out.size() + n > limit) throw new IOException("Read size limit exceeded");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public static boolean safePath(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) return false;
        return Arrays.stream(name.split("/", -1)).noneMatch(p -> p.equals("..") || p.equals("."));
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
