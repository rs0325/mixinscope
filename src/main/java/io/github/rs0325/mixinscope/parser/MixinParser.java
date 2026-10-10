package io.github.rs0325.mixinscope.parser;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

public final class MixinParser {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Set<String> INJECTORS = Set.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant");
    private static final String EXTRAS = "Lcom/llamalad7/mixinextras/injector/";
    private static final Set<String> EXTRAS_ANNOTATIONS = Set.of(EXTRAS + "wrapoperation/WrapOperation;", EXTRAS + "wrapmethod/WrapMethod;",
            EXTRAS + "ModifyExpressionValue;", EXTRAS + "ModifyReturnValue;", EXTRAS + "WrapWithCondition;", EXTRAS + "v2/WrapWithCondition;");
    private final ScanOptions options;
    private final Report report;
    public MixinParser(ScanOptions options, Report report) { this.options = options; this.report = report; }
    public void parse() {
        Map<String, Artifact> artifacts = new HashMap<>();
        for (Artifact a : report.artifacts) artifacts.put(a.hash, a);
        Map<String, Modification> deduplicated = new TreeMap<>();
        for (MixinConfig config : report.configs) {
            options.checkBudget();
            Artifact artifact = artifacts.get(config.artifact);
            for (String field : List.of("mixins", "client", "server")) {
                Set<String> side = new TreeSet<>(config.sides);
                if (!field.equals("mixins")) side.retainAll(Util.sides(field));
                for (String name : Util.strings(config.raw.get(field))) {
                    options.checkBudget();
                    String className = (config.packageName.isEmpty() ? "" : config.packageName + ".") + name;
                    String path = Util.owner(className) + ".class";
                    byte[] bytes = artifact.entries.get(path);
                    if (bytes == null) { report.diagnostic("MIXIN_CLASS_MISSING", artifact.hash + "!/" + path, "Registered mixin class missing", true); continue; }
                    try {
                        ClassNode node = classNode(bytes, true);
                        if (!node.name.equals(Util.owner(className))) {
                            report.diagnostic("CLASS_NAME_MISMATCH", path, "Declared class path differs from class-file internal name", true); continue;
                        }
                        AnnotationNode mixin = annotations(node.visibleAnnotations, node.invisibleAnnotations).stream()
                                .filter(a -> a.desc.equals(MIXIN)).findFirst().orElse(null);
                        if (mixin == null) { report.diagnostic("MIXIN_ANNOTATION_MISSING", className, "Registered class lacks @Mixin", false); continue; }
                        Map<String, Object> declaration = values(mixin);
                        List<String> owners = new ArrayList<>();
                        owners.addAll(Util.strings(declaration.get("value"))); owners.addAll(Util.strings(declaration.get("targets")));
                        owners = owners.stream().map(MixinParser::classOwner).distinct().sorted().toList();
                        if (owners.isEmpty()) report.diagnostic("MIXIN_TARGET_MISSING", className, "No static mixin target classes", false);
                        for (MethodNode method : node.methods) {
                            List<AnnotationNode> annotations = annotations(method.visibleAnnotations, method.invisibleAnnotations);
                            boolean recognized = false;
                            for (AnnotationNode annotation : annotations) {
                                String kind = simpleName(annotation.desc);
                                boolean injector = INJECTORS.contains(kind) && annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/" + kind + ";");
                                boolean extras = EXTRAS_ANNOTATIONS.contains(annotation.desc);
                                boolean overwrite = annotation.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;");
                                boolean reference = annotation.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;") ||
                                        annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") || annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;");
                                if (!(injector || extras || overwrite || reference)) {
                                    if ((annotation.desc.contains("/injection/") || annotation.desc.contains("/mixinextras/")) &&
                                            !annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Coerce;"))
                                        report.diagnostic("ANNOTATION_UNSUPPORTED", className + "." + method.name, annotation.desc, false);
                                    continue;
                                }
                                recognized = true;
                                Modification m = new Modification();
                                m.artifact = artifact.hash; m.config = config.path; m.mixin = className;
                                m.handler = method.name; m.handlerDescriptor = method.desc; m.kind = kind;
                                m.annotationDescriptor = annotation.desc;
                                m.owners = owners; m.referenceOnly = reference; m.sides = new TreeSet<>(side);
                                m.applicability = side.isEmpty() ? Applicability.INELIGIBLE : config.applicability;
                                m.options = values(annotation); m.options.put("mixinDeclaration", declaration);
                                List<Map<String, Object>> parameters = parameterAnnotations(method);
                                if (!parameters.isEmpty()) {
                                    m.options.put("parameterAnnotations", parameters);
                                    if (parameters.stream().anyMatch(p -> p.get("annotationType").toString().contains("/sugar/")))
                                        report.diagnostic("EXTRAS_LOCAL_UNSUPPORTED", className + "." + method.name, "Parameter sugar metadata retained; local capture is not resolved", false);
                                }
                                for (AnnotationNode extra : annotations) if (!extra.desc.equals(annotation.desc)) m.options.put("annotation:" + extra.desc, values(extra));
                                m.defaults.put("mixinPriority", Util.integer(declaration.get("priority"), Util.integer(config.raw.get("mixinPriority"), 1000)));
                                m.defaults.put("configPriority", Util.integer(config.raw.get("priority"), 1000));
                                m.defaults.put("injectorOrder", Util.integer(m.options.get("order"), kind.equals("Redirect") ? 10000 : 1000));
                                int require = Util.integer(m.options.get("require"), -1);
                                m.defaults.put("require", require < 0 ? Util.integer(Util.map(config.raw.get("injectors")).get("defaultRequire"), 0) : require);
                                m.defaults.put("expect", Util.integer(m.options.get("expect"), 1));
                                m.defaults.put("allow", Util.integer(m.options.get("allow"), -1));
                                m.defaults.put("remap", Util.bool(m.options.get("remap"), true));
                                m.defaults.put("semanticsProfile", knownProfile() ? "Mixin-0.8.7" : "ASSUMED:Mixin-0.8.7");
                                if (extras) ExtrasProfiles.describe(m, method, options.mixinExtrasVersion, report);
                                m.selectors = new ArrayList<>(Util.strings(m.options.get("method")));
                                if (overwrite) m.selectors.add(method.name + method.desc);
                                for (Object desc : Util.list(m.options.get("target"))) {
                                    Map<String, Object> d = Util.map(desc);
                                    if (!d.isEmpty()) m.selectors.add(descSelector(d));
                                }
                                if (reference) m.selectors.add(Util.str(m.options.get("value"), method.name));
                                for (Object at : Util.list(m.options.get("at"))) if (at instanceof Map<?, ?>) m.at.add(Util.map(at));
                                if (m.options.containsKey("constant")) {
                                    Map<String, Object> at = new TreeMap<>(); at.put("value", "CONSTANT"); at.put("constant", m.options.get("constant")); m.at.add(at);
                                }
                                if (!reference && m.selectors.isEmpty()) report.diagnostic("SELECTOR_MISSING", className + "." + method.name, "No supported target selectors", false);
                                if (m.options.containsKey("annotation:Lcom/llamalad7/mixinextras/expression/Expression;") ||
                                        annotations.stream().anyMatch(a -> a.desc.contains("/expression/") || a.desc.contains("/sugar/")))
                                    report.diagnostic("EXTRAS_EXPRESSION_UNSUPPORTED", className + "." + method.name, "Expression / sugar metadata retained; resolution unsupported", false);
                                m.id = Util.id(m.artifact, m.config, m.mixin, m.handler, m.handlerDescriptor, annotation.desc);
                                Modification prior = deduplicated.get(m.id);
                                if (prior == null) deduplicated.put(m.id, m);
                                else { prior.sides.addAll(m.sides); if (m.applicability != Applicability.INELIGIBLE && prior.applicability == Applicability.INELIGIBLE) prior.applicability = m.applicability; }
                            }
                            if (!recognized && !method.name.startsWith("<") && annotations.stream().noneMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Unique;")))
                                report.diagnostic("METHOD_MERGE_UNSUPPORTED", className + "." + method.name + method.desc,
                                        "Non-injector merge / intrinsic method may modify the target; effects are not analyzed", false);
                        }
                    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                        report.diagnostic("CLASS_UNSUPPORTED_OR_INVALID", artifact.hash + "!/" + path, String.valueOf(e.getMessage()), true);
                    }
                }
            }
        }
        report.modifications.addAll(deduplicated.values());
        List<String> extrasCandidates = report.artifacts.stream().flatMap(a -> a.mods.stream())
                .filter(m -> m.id().equals("mixinextras")).map(ModIdentity::version).distinct().sorted().toList();
        if (!extrasCandidates.isEmpty()) report.environment.put("mixinExtrasCandidates", extrasCandidates);
        if (report.modifications.stream().anyMatch(m -> EXTRAS_ANNOTATIONS.contains(m.annotationDescriptor))) {
            if (!ExtrasProfiles.known(options.mixinExtrasVersion)) report.diagnostic("EXTRAS_VERSION_UNVERIFIED", "profile",
                    "No verified MixinExtras defaults for declared version " + options.mixinExtrasVersion + "; bundled candidates=" + extrasCandidates + ". Selection is not emulated", false);
            else if (!extrasCandidates.isEmpty() && !extrasCandidates.contains(options.mixinExtrasVersion)) report.diagnostic("EXTRAS_VERSION_CANDIDATE_MISMATCH", "profile",
                    "Declared MixinExtras profile differs from bundled candidates; loader-provided versions may exist", false);
        }
        if (options.mixinVersion.equals("unknown")) report.diagnostic("MIXIN_VERSION_ASSUMED", "profile", "Defaults assume Mixin 0.8.7; actual priority / order semantics are unverified", false);
        else if (!knownProfile()) report.diagnostic("MIXIN_VERSION_UNSUPPORTED", "profile", "Declared version " + options.mixinVersion + " has no verified defaults profile; Mixin 0.8.7 defaults are assumed", false);
    }
    private boolean knownProfile() { return options.mixinVersion.equals("0.8.7") || options.mixinVersion.endsWith("+mixin.0.8.7"); }
    public static ClassNode classNode(byte[] bytes, boolean code) {
        if (bytes.length < 8 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe)
            throw new IllegalArgumentException("Invalid class file header");
        int major = (bytes[6] & 255) * 256 + (bytes[7] & 255);
        if (major > 72 || major < 45) throw new IllegalArgumentException("Unsupported class-file major " + major + " (supported 45–72)");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, code ? ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES : ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
    private static String classOwner(String value) { return Util.owner(value.startsWith("L") && value.endsWith(";") ? value.substring(1, value.length() - 1) : value); }
    private static String descSelector(Map<String, Object> desc) {
        String owner = Util.str(desc.get("owner"), "");
        if (owner.equals("V")) owner = "";
        String name = Util.str(desc.get("value"), "");
        StringBuilder descriptor = new StringBuilder("(");
        for (String arg : Util.strings(desc.get("args"))) descriptor.append(arg);
        descriptor.append(')').append(Util.str(desc.get("ret"), "V"));
        return (owner.isEmpty() ? "" : owner) + name + descriptor;
    }
    public static String simpleName(String descriptor) { return descriptor.substring(descriptor.lastIndexOf('/') + 1, descriptor.length() - 1); }
    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>(); if (visible != null) result.addAll(visible); if (invisible != null) result.addAll(invisible); return result;
    }
    private static List<Map<String, Object>> parameterAnnotations(MethodNode method) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<AnnotationNode>[] groups : Arrays.asList(method.visibleParameterAnnotations, method.invisibleParameterAnnotations)) {
            if (groups == null) continue;
            for (int i = 0; i < groups.length; i++) if (groups[i] != null) for (AnnotationNode node : groups[i])
                result.add(Map.of("index", i, "annotationType", node.desc, "values", values(node)));
        }
        return result;
    }
    public static Map<String, Object> values(AnnotationNode node) {
        Map<String, Object> result = new TreeMap<>();
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) result.put(node.values.get(i).toString(), value(node.values.get(i + 1)));
        return result;
    }
    private static Object value(Object v) {
        if (v instanceof Type t) return t.getDescriptor();
        if (v instanceof AnnotationNode a) { Map<String, Object> map = values(a); map.put("annotationType", a.desc); return map; }
        if (v instanceof List<?> l) return l.stream().map(MixinParser::value).toList();
        if (v instanceof String[] e) return e[1];
        return v;
    }
}
