package io.github.rs0325.mixinscope.loader;

import io.github.rs0325.mixinscope.archive.ArchiveReader;
import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.tomlj.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Manifest;

/** Static metadata adapters. Loader selection and arbitrary dependency solving are not emulated. */
public final class Discovery {
    private final ScanOptions options;
    private final Report report;
    private final ArchiveReader archives;
    private final Set<String> visited = new HashSet<>();
    public Discovery(ScanOptions options, Report report, ArchiveReader archives) {
        this.options = options; this.report = report; this.archives = archives;
    }
    public void discover(List<Artifact> roots) { for (Artifact a : roots) visit(a, 0); checkDuplicates(); checkRequiredMods(); }
    private void visit(Artifact a, int depth) {
        options.checkBudget();
        if (!visited.add(a.hash)) return;
        List<String> nested = new ArrayList<>();
        List<Registration> registrations = new ArrayList<>();
        Set<String> side = Util.sides("both");
        try {
            if (a.entries.containsKey("quilt.mod.json")) {
                a.loader = "quilt";
                Map<String, Object> root = Util.json(a.entries.get("quilt.mod.json"));
                if (Util.integer(root.get("schema_version"), -1) != 1) unsupported(a, "Unknown Quilt metadata schema");
                Map<String, Object> q = Util.map(root.get("quilt_loader"));
                Map<String, Object> metadata = Util.map(q.get("metadata"));
                addMod(a, q, metadata); a.metadata.put("loaderMetadata", root);
                side = declaredSide(Util.map(root.get("minecraft")).get("environment"), a);
                register(a, registrations, root.get("mixin"), side, List.of());
                nested.addAll(Util.strings(q.get("jars")));
                for (String path : Util.strings(root.get("access_widener"))) transformer(a, "ACCESS_CONTROL", path);
                if (q.containsKey("plugins")) transformer(a, "DYNAMIC_BOOTSTRAP", "quilt.mod.json:quilt_loader.plugins");
            } else if (a.entries.containsKey("fabric.mod.json")) {
                a.loader = "fabric";
                Map<String, Object> root = Util.json(a.entries.get("fabric.mod.json"));
                if (Util.integer(root.get("schemaVersion"), -1) != 1) unsupported(a, "Unknown Fabric metadata schema");
                addMod(a, root, root); a.metadata.put("loaderMetadata", root);
                side = declaredSide(root.get("environment"), a);
                register(a, registrations, root.get("mixins"), side, List.of());
                for (Object value : Util.list(root.get("jars"))) {
                    String file = Util.str(Util.map(value).get("file"), "");
                    if (!file.isEmpty()) nested.add(file);
                }
                for (String key : List.of("accessWidener", "classTweaker")) {
                    if (root.containsKey(key)) transformer(a, "ACCESS_CONTROL", root.get(key).toString());
                }
                Map<String, Object> entrypoints = Util.map(root.get("entrypoints"));
                if (entrypoints.containsKey("preLaunch")) transformer(a, "DYNAMIC_BOOTSTRAP", "fabric.mod.json:entrypoints.preLaunch");
            } else {
                String file = a.entries.containsKey("META-INF/neoforge.mods.toml") ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
                if (a.entries.containsKey(file)) {
                    a.loader = file.contains("neoforge") || options.loader.equals("neoforge") ? "neoforge" : "forge";
                    TomlParseResult toml = Toml.parse(new String(a.entries.get(file), StandardCharsets.UTF_8));
                    if (toml.hasErrors()) throw new IOException("Invalid TOML metadata: " + toml.errors());
                    a.metadata.put("loaderMetadata", tomlTree(toml));
                    TomlArray mods = toml.getArray("mods");
                    if (mods != null) for (int i = 0; i < mods.size(); i++) {
                        TomlTable m = mods.getTable(i);
                        a.mods.add(new ModIdentity(Util.str(m.get("modId"), "unknown"),
                                Util.str(m.get("version"), "unknown"), Util.str(m.get("displayName"), "unknown")));
                    }
                    TomlArray mixins = toml.getArray("mixins");
                    if (mixins != null) for (int i = 0; i < mixins.size(); i++) {
                        TomlTable m = mixins.getTable(i);
                        registrations.add(new Registration(Util.str(m.get("config"), ""), side,
                                Util.strings(tomlTree(m.get("requiredMods"))), Util.str(m.get("behaviorVersion"), "")));
                        if (m.contains("behaviorVersion")) a.metadata.put("behaviorVersion", m.get("behaviorVersion"));
                    }
                }
                if (a.entries.containsKey("META-INF/jarjar/metadata.json")) {
                    Map<String, Object> jarjar = Util.json(a.entries.get("META-INF/jarjar/metadata.json"));
                    a.metadata.put("jarJarMetadata", jarjar);
                    for (Object value : Util.list(jarjar.get("jars"))) {
                        String path = Util.str(Util.map(value).get("path"), "");
                        if (!path.isEmpty()) nested.add(path);
                    }
                }
            }
            if (a.entries.containsKey("META-INF/MANIFEST.MF")) {
                Manifest m = new Manifest(new ByteArrayInputStream(a.entries.get("META-INF/MANIFEST.MF")));
                String configs = m.getMainAttributes().getValue("MixinConfigs");
                if (configs != null) for (String config : configs.split(",")) registrations.add(new Registration(config.trim(), side, List.of()));
                for (String key : List.of("FMLCorePlugin", "TweakClass"))
                    if (m.getMainAttributes().getValue(key) != null) transformer(a, "DYNAMIC_BOOTSTRAP", "MANIFEST.MF:" + key);
            }
            for (String path : List.of("META-INF/accesstransformer.cfg", "META-INF/coremods.json",
                    "META-INF/services/cpw.mods.modlauncher.api.ITransformationService")) {
                if (a.entries.containsKey(path)) transformer(a, path.endsWith(".cfg") ? "ACCESS_CONTROL" : "BODY_TRANSFORMER", path);
            }
            for (String path : a.entries.keySet()) {
                if (path.endsWith(".accesswidener") || path.endsWith(".classtweaker")) transformer(a, "ACCESS_CONTROL_CANDIDATE", path);
            }
            if (a.mods.isEmpty()) a.mods.add(new ModIdentity("artifact:" + a.hash.substring(0, 12), "unknown", "Unattributed artifact"));
            if (a.mods.size() > 1) report.diagnostic("OWNERSHIP_AMBIGUOUS", a.hash, "Multiple mods share this artifact; mixins are attributed to the artifact, not duplicated per mod", false);
            if (a.loader.equals("unknown")) report.diagnostic("LOADER_UNKNOWN", a.hash, "No recognized loader metadata; dynamic registration is not discoverable", false);
            if (!options.loader.equals("auto") && !compatible(a.loader, options.loader)) {
                report.diagnostic("LOADER_MISMATCH", a.hash, "Artifact loader " + a.loader + " differs from selected loader " + options.loader, false);
                side = Set.of();
            }
            Set<String> finalSide = side;
            Set<String> registered = new HashSet<>();
            for (Registration reg : registrations) {
                options.checkBudget();
                // Do not collapse distinct registrations of the same config for different sides.
                String key = reg.path + reg.sides + reg.required + reg.behavior;
                if (registered.add(key)) config(a, reg, finalSide);
            }
            for (String path : a.entries.keySet()) {
                if (path.endsWith(".json") && path.toLowerCase(Locale.ROOT).contains("mixin") && registrations.stream().noneMatch(r -> r.path.equals(path)))
                    report.diagnostic("UNREGISTERED_CONFIG_CANDIDATE", a.hash + "!/" + path, "Not registered by supported static metadata", false);
            }
        } catch (IOException | RuntimeException e) { a.partial = true; report.diagnostic("METADATA_ERROR", a.hash, String.valueOf(e.getMessage()), true); }
        for (String path : new TreeSet<>(nested)) {
            options.checkBudget();
            Artifact child = archives.nested(a, path, depth + 1);
            if (child != null) { report.diagnostic("NESTED_SELECTION_UNKNOWN", child.hash, "Nested candidate selection is not emulated", false); visit(child, depth + 1); }
        }
    }
    private record Registration(String path, Set<String> sides, List<String> required, String behavior) {
        Registration(String path, Set<String> sides, List<String> required) { this(path, sides, required, ""); }
    }
    private void register(Artifact a, List<Registration> result, Object values, Set<String> sides, List<String> required) {
        for (Object value : Util.list(values)) {
            options.checkBudget();
            if (value instanceof String s) result.add(new Registration(s, sides, required));
            else {
                Map<String, Object> m = Util.map(value);
                Set<String> applicable = new TreeSet<>(sides);
                applicable.retainAll(declaredSide(m.get("environment"), a));
                result.add(new Registration(Util.str(m.get("config"), ""), applicable, required));
            }
        }
    }
    private void config(Artifact a, Registration reg, Set<String> artifactSide) throws IOException {
        if (!ArchiveReader.safePath(reg.path)) throw new IOException("Unsafe mixin config path");
        byte[] bytes = a.entries.get(reg.path);
        if (bytes == null) { report.diagnostic("CONFIG_MISSING", a.hash + "!/" + reg.path, "Registered mixin config missing", true); return; }
        MixinConfig c = new MixinConfig(); c.artifact = a.hash; c.path = reg.path; c.raw = Util.json(bytes);
        c.packageName = Util.str(c.raw.get("package"), ""); c.plugin = Util.str(c.raw.get("plugin"), "");
        c.refmap = Util.str(c.raw.get("refmap"), ""); c.sides = new TreeSet<>(reg.sides); c.sides.retainAll(artifactSide);
        c.requiredMods = new ArrayList<>(reg.required);
        c.registration.put("requiredMods", reg.required); c.registration.put("behaviorVersion", reg.behavior);
        if (c.sides.isEmpty()) c.applicability = Applicability.INELIGIBLE;
        else if (!c.plugin.isEmpty() || a.nested) c.applicability = Applicability.CONDITIONAL;
        if ((a.partial || Boolean.TRUE.equals(a.metadata.get("schemaUnknown")) || Boolean.TRUE.equals(a.metadata.get("sideUnknown"))) && c.applicability != Applicability.INELIGIBLE) c.applicability = Applicability.UNKNOWN;
        boolean fmlProfile=options.metadataProfile.equals("fml-12.0")&&a.loader.equals("neoforge");
        c.registration.put("conditionProfile",fmlProfile?"DECLARED:fml-12.0":"UNVERIFIED");
        if ((!reg.required.isEmpty() || !reg.behavior.isEmpty()) && !fmlProfile) {
            c.applicability = c.sides.isEmpty() ? Applicability.INELIGIBLE : Applicability.UNKNOWN;
            report.diagnostic("METADATA_CONDITION_PROFILE_UNKNOWN", c.path, "New FML mixin attributes require explicit --metadata-profile fml-12.0; they are not applied to older profiles", false);
        }
        if (fmlProfile) FmlMetadataProfile.evaluateBehavior(c,reg.behavior,report);
        else if (!reg.behavior.isEmpty()) {
            if (!c.sides.isEmpty()) c.applicability = Applicability.UNKNOWN;
            report.diagnostic("BEHAVIOR_VERSION_UNVERIFIED", c.path, "behaviorVersion is retained; loader-supported bounds and semantics are not emulated", false);
        }
        if (!c.plugin.isEmpty()) report.diagnostic("PLUGIN_NOT_EXECUTED", a.hash + "!/" + c.path,
                "Plugin may veto, supply extra mixins/refmaps, or transform target bytecode: " + c.plugin, false);
        if (!c.refmap.isEmpty()) {
            byte[] refmap = a.entries.get(c.refmap);
            if (refmap == null) report.diagnostic("REFMAP_MISSING", a.hash + "!/" + c.refmap, "Reference map missing", false);
            else c.references = Util.json(refmap);
        }
        report.configs.add(c);
    }
    private void checkDuplicates() {
        Map<String, Set<String>> owners = new TreeMap<>();
        for (Artifact a : report.artifacts) for (ModIdentity mod : a.mods) owners.computeIfAbsent(mod.id(), k -> new TreeSet<>()).add(a.hash);
        for (var e : owners.entrySet()) if (e.getValue().size() > 1) {
            report.diagnostic("DUPLICATE_MOD_ID", e.getKey(), "Multiple artifacts declare this mod ID; loader version selection is unresolved", false);
            for (MixinConfig c : report.configs) if (e.getValue().contains(c.artifact) && c.applicability != Applicability.INELIGIBLE) c.applicability = Applicability.UNKNOWN;
        }
    }
    private void checkRequiredMods() {
        Set<String> ids = new HashSet<>();
        for (Artifact a : report.artifacts) for (ModIdentity mod : a.mods) ids.add(mod.id());
        if(!options.gameVersion.equals("unknown"))ids.add("minecraft");
        if(options.loader.equals("neoforge")&&!options.loaderVersion.equals("unknown"))ids.add("neoforge");
        for (MixinConfig c : report.configs) if (!c.requiredMods.isEmpty()) {
            if (!c.registration.get("conditionProfile").equals("DECLARED:fml-12.0")) {c.registration.put("requiredModsStatus","UNKNOWN_PROFILE");continue;}
            if (!ids.containsAll(c.requiredMods) && !report.coverage.partial) { c.registration.put("requiredModsStatus","MISSING_FROM_DECLARED_INPUT");c.applicability = Applicability.INELIGIBLE; report.diagnostic("REQUIRED_MOD_MISSING", c.path, "Required mods not present in the declared inputs/profile: " + c.requiredMods, false); }
            else {c.registration.put("requiredModsStatus",ids.containsAll(c.requiredMods)?"PRESENT_CANDIDATES":"UNKNOWN_PARTIAL_INPUT");if(c.applicability==Applicability.ELIGIBLE)c.applicability=Applicability.CONDITIONAL;}
        }
    }
    private void addMod(Artifact a, Map<String, Object> source, Map<String, Object> metadata) {
        a.mods.add(new ModIdentity(Util.str(source.get("id"), "unknown"), Util.str(source.get("version"), "unknown"), Util.str(metadata.get("name"), "unknown")));
    }
    private static Object tomlTree(Object value) {
        if (value instanceof TomlTable table) { Map<String, Object> result = new TreeMap<>(); table.toMap().forEach((key, item) -> result.put(key, tomlTree(item))); return result; }
        if (value instanceof TomlArray array) return array.toList().stream().map(Discovery::tomlTree).toList();
        if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        return value;
    }
    private Set<String> declaredSide(Object value, Artifact a) {
        String s = Util.str(value, "*");
        if (s.equals("dedicated_server")) s = "server";
        if (!Set.of("*", "both", "client", "server").contains(s)) {
            if (a != null) a.metadata.put("sideUnknown", true);
            report.diagnostic("SIDE_UNKNOWN", a == null ? "registration" : a.hash, "Unknown side value " + s, false);
        }
        return Util.sides(s);
    }
    private void unsupported(Artifact a, String reason) { a.metadata.put("schemaUnknown", true); report.diagnostic("METADATA_VERSION_UNKNOWN", a.hash, reason, false); }
    private void transformer(Artifact a, String kind, String resource) {
        Transformer t = new Transformer(a.hash, kind, resource, "Presence only; transformation effects are not analyzed or executed.");
        if (!report.transformers.contains(t)) { report.transformers.add(t); report.diagnostic("TRANSFORMER_NOT_ANALYZED", a.hash + "!/" + resource, t.limitation(), false); }
        if (!resource.contains(":" ) && !resource.startsWith("MANIFEST") && !a.entries.containsKey(resource)) report.diagnostic("TRANSFORMER_RESOURCE_MISSING", a.hash, "Declared resource missing: " + resource, false);
    }
    public static boolean compatible(String actual, String selected) { return actual.equals(selected) || actual.equals("fabric") && selected.equals("quilt"); }
}
