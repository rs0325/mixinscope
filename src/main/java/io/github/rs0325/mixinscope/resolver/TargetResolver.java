package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.objectweb.asm.Type;
import java.io.IOException;
import java.util.*;

public final class TargetResolver {
    public record Selector(String owner, String name, String descriptor) {}
    private final ScanOptions options;
    private final Report report;
    private TinyMappings mappings;
    private ClassIndex classes;
    private String namespace;
    public TargetResolver(ScanOptions options, Report report) { this.options = options; this.report = report; namespace = options.namespace; }
    public void resolve() {
        if (options.mappings != null) {
            try {
                if (options.namespace.equals("unknown") || options.outputNamespace == null) throw new IOException("Mappings require explicit --input-namespace and --output-namespace");
                if (options.mappingGameVersion != null && !options.mappingGameVersion.equals(options.gameVersion)) throw new IOException("Mapping game version differs from scan game version");
                mappings = new TinyMappings(options.mappings, options.namespace, options.outputNamespace, options.maxEntry);
                namespace = options.outputNamespace;
                report.environment.put("mapping", Map.of("hash", mappings.hash, "from", mappings.from, "to", mappings.to,
                        "gameVersion", options.mappingGameVersion == null ? "unverified" : options.mappingGameVersion));
                if (options.mappingGameVersion == null) report.diagnostic("MAPPING_VERSION_UNVERIFIED", "mappings", "Tiny v2 has no game version; use --mapping-game-version to declare provenance", false);
            } catch (IOException ex) { report.diagnostic("MAPPING_ERROR", "mappings", ex.getMessage(), true); }
        }
        String gameNs = options.gameNamespace == null ? namespace : options.gameNamespace;
        if (options.gameJar != null || !options.classpath.isEmpty()) {
            if (!gameNs.equals(namespace)) report.diagnostic("REFERENCE_NAMESPACE_MISMATCH", "game", "Reference namespace must equal resolved output namespace; reference remapping is not performed", true);
            else { classes = new ClassIndex(options, report); if (options.gameNamespace == null) report.assumptions.add("Reference JAR namespace is assumed to equal resolved target namespace: " + namespace); }
        }
        Map<String, MixinConfig> configs = new HashMap<>();
        Map<String, Artifact> artifacts = new HashMap<>();
        for (MixinConfig c : report.configs) configs.put(c.artifact + "\u0000" + c.path, c);
        for (Artifact a : report.artifacts) artifacts.put(a.hash, a);
        for (Modification m : report.modifications) {
            options.checkBudget();
            MixinConfig config = configs.get(m.artifact + "\u0000" + m.config);
            if (m.referenceOnly) continue;
            for (String owner : m.owners) for (String raw : m.selectors) {
                Target target = new Target(); target.rawOwner = owner; target.rawSelector = raw;
                m.targets.add(target);
                String selector = reference(config, m, raw);
                target.normalizedSelector = selector;
                try {
                    Selector s = parse(selector, owner);
                    String resolvedOwner = s.owner, name = s.name, descriptor = s.descriptor;
                    if (mappings != null && Util.bool(m.options.get("remap"), true)) {
                        name = mappings.method(resolvedOwner, name, descriptor);
                        descriptor = mappings.descriptor(descriptor); resolvedOwner = mappings.owner(resolvedOwner);
                    }
                    String targetNamespace = mappings != null && !Util.bool(m.options.get("remap"), true) ? options.namespace : namespace;
                    target.key = key(artifacts.get(m.artifact).loader, targetNamespace, resolvedOwner, name, descriptor);
                    target.resolution = !descriptor.isEmpty() && !name.contains("*") ? Resolution.RESOLVED : Resolution.AMBIGUOUS;
                    if (classes != null && !targetNamespace.equals(namespace)) {
                        report.diagnostic("LITERAL_NAMESPACE_UNVERIFIED", m.id, "remap=false selector remains in input namespace; output-namespace reference was not used", false);
                    } else if (classes != null) {
                        List<ClassIndex.Match> matches = classes.methods(resolvedOwner, name, descriptor);
                        target.candidates = matches.stream().map(v -> v.owner() + "." + v.method().name + v.method().desc).sorted().toList();
                        if (matches.size() == 1) {
                            ClassIndex.Match match = matches.getFirst();
                            target.key = key(artifacts.get(m.artifact).loader, targetNamespace, match.owner(), match.method().name, match.method().desc);
                            target.resolution = Resolution.RESOLVED; target.existence = Existence.VERIFIED; target.bytecodeHash = match.hash();
                            target.limitation = "Reference bytecode precedes other transformers; runtime application is unverified.";
                            InstructionMatcher.resolve(match.method(), m, target, mappings, rawReference -> reference(config, m, rawReference), report);
                        } else if (matches.size() > 1) target.resolution = Resolution.AMBIGUOUS;
                        else if (classes.contains(resolvedOwner) && classes.hierarchyComplete(resolvedOwner)) {
                            target.existence = Existence.MISSING; target.resolution = Resolution.UNRESOLVED;
                            report.diagnostic("TARGET_MISSING", target.key.display(), "No matching method in complete reference hierarchy", false);
                        } else { target.resolution = Resolution.UNRESOLVED; report.diagnostic("REFERENCE_HIERARCHY_INCOMPLETE", resolvedOwner, "Target or declaring superclass unavailable / ambiguous", false); }
                    }
                    if (target.resolution != Resolution.RESOLVED) report.diagnostic("TARGET_" + target.resolution, m.id, "Selector not uniquely resolved: " + owner + "." + raw, false);
                } catch (IllegalArgumentException ex) { report.diagnostic("SELECTOR_UNSUPPORTED", m.id, raw + ": " + ex.getMessage(), false); }
            }
        }
    }
    private TargetKey key(String loader, String targetNamespace, String owner, String name, String descriptor) {
        String profile = (options.loader.equals("auto") ? loader : options.loader) + ":" + options.loaderVersion;
        return new TargetKey(options.gameVersion, profile, targetNamespace, owner, name, descriptor);
    }
    private String reference(MixinConfig config, Modification m, String raw) {
        if (config == null || config.references.isEmpty() || !Util.bool(m.options.get("remap"), true)) return raw;
        Map<String, Object> context = Util.map(config.references.get("mappings"));
        if (options.refmapContext != null) {
            Map<String, Object> selected = Util.map(Util.map(config.references.get("data")).get(options.refmapContext));
            if (selected.isEmpty()) { report.diagnostic("REFMAP_CONTEXT_MISSING", m.config, "Requested refmap context not found", false); return raw; }
            context = selected;
        }
        Map<String, Object> refs = Util.map(context.get(m.mixin));
        if (refs.isEmpty()) refs = Util.map(context.get(Util.owner(m.mixin)));
        return Util.str(refs.get(raw), raw);
    }
    public static Selector parse(String raw, String fallbackOwner) {
        String owner = fallbackOwner, rest = raw;
        if (raw.startsWith("L") && raw.indexOf(';') > 1 && raw.indexOf(';') < (raw.indexOf('(') < 0 ? raw.length() : raw.indexOf('('))) {
            int end = raw.indexOf(';'); owner = raw.substring(1, end); rest = raw.substring(end + 1);
        }
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren), desc = paren < 0 ? "" : rest.substring(paren);
        if (name.isBlank() || name.contains("/") || name.contains(".") || name.contains(":") || name.contains("{") || name.contains("}")) throw new IllegalArgumentException("Custom selector or quantifier unsupported");
        if (!desc.isEmpty() && !validDescriptor(desc)) throw new IllegalArgumentException("Invalid JVM method descriptor");
        return new Selector(Util.owner(owner), name, desc);
    }
    public static boolean validDescriptor(String descriptor) {
        // ASM Type alone permits some malformed descriptors, so also consume the entire grammar.
        return descriptor.matches("\\((?:[ZBCSIJFD]|\\[+(?:[ZBCSIJFD]|L[^.;\\[()]+;)|L[^.;\\[()]+;)*\\)(?:V|[ZBCSIJFD]|\\[+(?:[ZBCSIJFD]|L[^.;\\[()]+;)|L[^.;\\[()]+;)");
    }
}
