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
        if (name.equals("<init>") || name.equals("<clinit>")) return result;
        Set<String> signatures = new HashSet<>();
        for (Match match : result) signatures.add(match.method.name + match.method.desc);
        List<Match> inherited = new ArrayList<>();
        if (node.superName != null) inherited.addAll(find(node.superName, name, descriptor, new HashSet<>(seen)));
        for (Match match : inherited) if (inheritable(match, owner) && !signatures.contains(match.method.name + match.method.desc)) result.add(match);
        for (Match match : result) signatures.add(match.method.name + match.method.desc);
        // Different interface declarations remain candidates unless a class declaration wins.
        for (String iface : node.interfaces) for (Match match : find(iface, name, descriptor, new HashSet<>(seen)))
            if ((match.method.access & (org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC)) == 0 && !signatures.contains(match.method.name + match.method.desc)) result.add(match);
        return result.stream().distinct().toList();
    }
    private static boolean inheritable(Match match, String child) {
        if (match.method.name.startsWith("<") || (match.method.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0) return false;
        int visibility = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_PROTECTED;
        return (match.method.access & visibility) != 0 || packageName(match.owner).equals(packageName(child));
    }
    private static String packageName(String owner) { int slash=owner.lastIndexOf('/');return slash<0?"":owner.substring(0,slash); }
    public boolean hierarchyComplete(String owner) { return complete(owner, new HashSet<>()); }
    private boolean complete(String owner, Set<String> seen) {
        if (owner == null || owner.equals("java/lang/Object") || !seen.add(owner)) return true;
        if (!contains(owner)) return false;
        ClassNode c = classes.get(owner);
        return complete(c.superName, seen) && c.interfaces.stream().allMatch(i -> complete(i, seen));
    }
}
