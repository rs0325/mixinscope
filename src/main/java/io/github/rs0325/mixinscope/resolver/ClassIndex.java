package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.archive.ArchiveReader;
import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.parser.MixinParser;
import org.objectweb.asm.tree.*;
import java.nio.file.Path;
import java.util.*;

/** Optional reference bytecode. It is never treated as transformed game bytecode. */
public final class ClassIndex {
    public record Match(String owner, MethodNode method, String hash) {}
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    public ClassIndex(ScanOptions options, Report report) {
        Report context = new Report(); ArchiveReader reader = new ArchiveReader(options, context);
        List<Path> paths = new ArrayList<>(); if (options.gameJar != null) paths.add(options.gameJar); paths.addAll(options.classpath);
        for (Artifact artifact : reader.roots(paths)) {
            for (var e : artifact.entries.entrySet()) if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/")) {
                try {
                    reader.checkTime();
                    ClassNode node = MixinParser.classNode(e.getValue(), true); String hash = Util.hash(e.getValue());
                    if (classes.containsKey(node.name) && !hashes.get(node.name).equals(hash)) {
                        ambiguous.add(node.name); report.diagnostic("CLASSPATH_AMBIGUOUS", node.name, "Different class definitions in reference inputs", false);
                    } else { classes.put(node.name, node); hashes.put(node.name, hash); }
                } catch (Exception ex) { report.diagnostic("REFERENCE_CLASS_ERROR", e.getKey(), String.valueOf(ex.getMessage()), true); }
            }
        }
        report.environment.put("referenceArtifacts", context.artifacts.stream().map(a -> a.hash).sorted().toList());
        for (Diagnostic diagnostic : context.coverage.diagnostics) report.diagnostic(diagnostic.code(), "reference:" + diagnostic.source(), diagnostic.message(), diagnostic.error());
    }
    public boolean contains(String owner) { return classes.containsKey(owner) && !ambiguous.contains(owner); }
    public boolean ambiguous(String owner) { return ambiguous.contains(owner); }
    public List<Match> methods(String owner, String name, String descriptor) {
        return find(owner, name, descriptor, new HashSet<>());
    }
    private List<Match> find(String owner, String name, String descriptor, Set<String> seen) {
        if (!seen.add(owner) || !contains(owner)) return List.of();
        ClassNode node = classes.get(owner);
        List<Match> result = new ArrayList<>();
        String pattern = java.util.regex.Pattern.quote(name).replace("*", "\\E.*\\Q");
        for (MethodNode m : node.methods) if (m.name.matches(pattern) && (descriptor.isEmpty() || m.desc.equals(descriptor))) result.add(new Match(owner, m, hashes.get(owner)));
        if (!result.isEmpty() || name.equals("<init>") || name.equals("<clinit>")) return result;
        if (node.superName != null) result.addAll(find(node.superName, name, descriptor, seen));
        if (result.isEmpty()) for (String iface : node.interfaces) result.addAll(find(iface, name, descriptor, seen));
        return result.stream().distinct().toList();
    }
    public boolean hierarchyComplete(String owner) { return complete(owner, new HashSet<>()); }
    private boolean complete(String owner, Set<String> seen) {
        if (owner == null || owner.equals("java/lang/Object") || !seen.add(owner)) return true;
        if (!contains(owner)) return false;
        ClassNode c = classes.get(owner);
        return complete(c.superName, seen) && c.interfaces.stream().allMatch(i -> complete(i, seen));
    }
}
