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
        long created=0,skipped=0;
        for (Modification m : report.modifications) {
            options.checkBudget();
            MixinConfig config = configs.get(m.artifact + "\u0000" + m.config);
            if (m.referenceOnly) continue;
            InjectionCandidates.normalize(m,mappings,options.namespace,Util.bool(m.options.get("remap"),true)?namespace:options.namespace,rawReference->reference(config,m,rawReference),report);
            long attempted=0,total=(long)m.owners.size()*m.selectors.size();
            combinations: for (String owner : m.owners) for (String raw : m.selectors) {
                options.checkBudget();
                if(created>=options.maxTargets)break combinations;created++;attempted++;
                Target target = new Target(); target.rawOwner = owner; target.rawSelector = raw;
                m.targets.add(target);
                String selector = reference(config, m, raw);
                target.normalizedSelector = selector;
                try {
                    String fallbackOwner=owner;
                    for(String declared:Util.strings(Util.map(m.options.get("mixinDeclaration")).get("targets")))if(Util.owner(declared).equals(owner)) {
                        fallbackOwner=Util.owner(reference(config,m,declared,true));break;
                    }
                    Selector s = parse(selector, fallbackOwner);
                    if(!s.owner.equals(fallbackOwner))throw new IllegalArgumentException("Selector owner differs from the declared Mixin target in the current namespace; cross-namespace aliases remain unverified and a selector cannot redirect the Mixin to another class");
                    String resolvedOwner = s.owner, name = s.name, descriptor = s.descriptor;
                    if (mappings != null && Util.bool(m.options.get("remap"), true)) {
                        name = mappings.method(resolvedOwner, name, descriptor);
                        descriptor = mappings.descriptor(descriptor); resolvedOwner = mappings.owner(resolvedOwner);
                    }
                    String targetNamespace = mappings != null && !Util.bool(m.options.get("remap"), true) ? options.namespace : namespace;
                    target.key = key(artifacts.get(m.artifact).loader, targetNamespace, resolvedOwner, name, descriptor);
                    target.limitation = "Owner is the declared Mixin target. Its declaring superclass and target existence are unverified without reference classpath. Runtime application is unverified.";
                    target.resolution = !descriptor.isEmpty() && !name.contains("*") ? Resolution.RESOLVED : Resolution.AMBIGUOUS;
                    if (classes != null && !targetNamespace.equals(namespace)) {
                        report.diagnostic("LITERAL_NAMESPACE_UNVERIFIED", m.id, "remap=false selector remains in input namespace; output-namespace reference was not used", false);
                    } else if (classes != null) {
                        List<ClassIndex.Match> matches = classes.declaredMethods(resolvedOwner, name, descriptor);
                        target.candidateSource = "REFERENCE_BYTECODE";
                        target.candidateKeys = matches.stream().map(v -> key(artifacts.get(m.artifact).loader, targetNamespace, v.owner(), v.method().name, v.method().desc))
                                .distinct().sorted(Comparator.comparing(TargetKey::display)).limit(options.maxSelectorCandidates).toList();
                        target.candidates = target.candidateKeys.stream().map(TargetKey::display).toList();
                        if (matches.size() > options.maxSelectorCandidates) candidateLimit(m, target);
                        if (matches.size() == 1) {
                            ClassIndex.Match match = matches.getFirst();
                            target.key = key(artifacts.get(m.artifact).loader, targetNamespace, match.owner(), match.method().name, match.method().desc);
                            target.resolution = Resolution.RESOLVED; target.existence = Existence.VERIFIED; target.bytecodeHash = match.hash();
                            target.declaringOwner = match.owner();
                            target.limitation = "Reference bytecode precedes other transformers; runtime application is unverified.";
                            InstructionMatcher.resolve(match.method(), m, target, mappings, rawReference -> reference(config, m, rawReference), report);
                        } else if (!matches.isEmpty()) {
                            target.resolution = Resolution.AMBIGUOUS;
                        }
                        else {
                            // Hierarchy lookup is evidence about inheritance only, never an injection target.
                            List<ClassIndex.Match> inherited=classes.methods(resolvedOwner,name,descriptor);
                            target.candidatesTruncated=classes.lookupTruncated();
                            target.resolution=Resolution.UNRESOLVED;
                            if(!inherited.isEmpty()) {
                                if(inherited.size()==1)target.declaringOwner=inherited.getFirst().owner();
                                target.limitation="Only inherited declarations were found. Mixin root selectors and Overwrite target the declared class's own methods; inherited bytecode is not a verified modification target. Other transformer merges remain unverified.";
                                report.diagnostic("TARGET_INHERITED_NOT_LOCAL",m.id,"No matching declaration in "+resolvedOwner+"; inherited declarations are informational only: "+Util.previewJson(inherited.stream().limit(8).map(v->v.owner()+"."+v.method().name+v.method().desc).toList(),1024),false);
                            } else if(classes.contains(resolvedOwner)&&classes.hierarchyComplete(resolvedOwner)) {
                                target.existence=Existence.MISSING;
                                report.diagnostic("TARGET_MISSING",target.key.display(),"No matching method declaration in reference target class",false);
                            } else report.diagnostic("REFERENCE_HIERARCHY_INCOMPLETE",resolvedOwner,"Target or hierarchy unavailable / ambiguous; missing declaration remains unverified",false);
                        }
                    }
                    if (target.resolution != Resolution.RESOLVED) report.diagnostic("TARGET_" + target.resolution, m.id, "Selector not uniquely resolved: " + owner + "." + raw, false);
                } catch (IllegalArgumentException ex) { report.diagnostic("SELECTOR_UNSUPPORTED", m.id, raw + ": " + ex.getMessage(), false); }
            }
            skipped+=total-attempted;
        }
        report.coverage.skippedTargets=(int)Math.min(Integer.MAX_VALUE,skipped);
        if(skipped>0)report.diagnostic("TARGET_LIMIT","resolver",skipped+" owner/selector combinations skipped after limit "+options.maxTargets+"; raw declarations are retained",false);
        declarationCandidates();
    }
    private record OwnerKey(String game, String loader, String namespace, String owner) {
        OwnerKey(TargetKey key) { this(key.gameVersion(), key.loaderProfile(), key.namespace(), key.owner()); }
    }
    private void declarationCandidates() {
        Map<OwnerKey, Set<TargetKey>> declarations = new HashMap<>();
        for (Modification m : report.modifications) for (Target t : m.targets)
            if (t.key != null && t.resolution == Resolution.RESOLVED && t.existence != Existence.MISSING)
                declarations.computeIfAbsent(new OwnerKey(t.key), k -> new TreeSet<>(Comparator.comparing(TargetKey::display))).add(t.key);
        for (Modification m : report.modifications) for (Target t : m.targets) {
            options.checkBudget();
            if (t.key == null || t.resolution != Resolution.AMBIGUOUS || !t.candidateSource.equals("NONE")) continue;
            List<TargetKey> matches = new ArrayList<>(); int count = 0;
            for (TargetKey key : declarations.getOrDefault(new OwnerKey(t.key), Set.of())) {
                options.checkBudget();
                if (Util.matchesGlob(t.key.name(),key.name(),options::checkBudget) && (t.key.descriptor().isEmpty() || t.key.descriptor().equals(key.descriptor()))) {
                    count++; if (matches.size() < options.maxSelectorCandidates) matches.add(key);
                }
            }
            t.candidateKeys = matches; t.candidates = matches.stream().map(TargetKey::display).toList();
            if (!matches.isEmpty()) {
                t.candidateSource = "MOD_DECLARATIONS";
                t.limitation = "Candidates come from other static Mod declarations, not a complete method inventory. A single candidate does not resolve this selector. Declaring owner and runtime application remain unverified.";
            }
            if (count > options.maxSelectorCandidates) candidateLimit(m, t);
        }
    }
    private void candidateLimit(Modification m, Target target) {
        target.candidatesTruncated = true;
        report.diagnostic("SELECTOR_CANDIDATE_LIMIT", m.id, "Candidate list truncated after " + options.maxSelectorCandidates + "; absence of overlap is not conclusive", false);
    }
    private TargetKey key(String loader, String targetNamespace, String owner, String name, String descriptor) {
        String profile = (options.loader.equals("auto") ? loader : options.loader) + ":" + options.loaderVersion;
        return new TargetKey(options.gameVersion, profile, targetNamespace, owner, name, descriptor);
    }
    private String reference(MixinConfig config, Modification m, String raw) {
        return reference(config,m,raw,Util.bool(m.options.get("remap"),true));
    }
    private String reference(MixinConfig config, Modification m, String raw, boolean enabled) {
        if (config == null || config.references.isEmpty() || !enabled) return raw;
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
        // Consume the JVM grammar iteratively: large valid/invalid strings must not overflow a regex stack.
        if(descriptor.isEmpty()||descriptor.charAt(0)!='(')return false;
        int i=1,slots=0;
        while(i<descriptor.length()&&descriptor.charAt(i)!=')') {
            int start=i,end=fieldEnd(descriptor,i);if(end<0)return false;
            char type=descriptor.charAt(start);slots+=type=='J'||type=='D'?2:1;if(slots>255)return false;i=end;
        }
        if(i>=descriptor.length()||++i>=descriptor.length())return false;
        return descriptor.charAt(i)=='V'?i+1==descriptor.length():fieldEnd(descriptor,i)==descriptor.length();
    }
    public static boolean validFieldDescriptor(String descriptor){return !descriptor.isEmpty()&&fieldEnd(descriptor,0)==descriptor.length();}
    private static int fieldEnd(String descriptor,int start) {
        int i=start,dimensions=0;
        while(i<descriptor.length()&&descriptor.charAt(i)=='['){i++;if(++dimensions>255)return -1;}
        if(i>=descriptor.length())return -1;
        char type=descriptor.charAt(i++);if("ZBCSIJFD".indexOf(type)>=0)return i;
        if(type!='L')return -1;int nameStart=i;
        while(i<descriptor.length()&&descriptor.charAt(i)!=';') {
            if(".[()".indexOf(descriptor.charAt(i))>=0)return -1;i++;
        }
        return i>nameStart&&i<descriptor.length()?i+1:-1;
    }
}
