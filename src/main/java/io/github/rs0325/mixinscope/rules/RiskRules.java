package io.github.rs0325.mixinscope.rules;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import java.util.*;

public final class RiskRules {
    private record Change(Modification modification, Target target) {}
    private final ScanOptions options;
    private final Report report;
    private final Map<String, Artifact> artifacts = new HashMap<>();
    private final Map<String, List<Change>> index = new TreeMap<>();
    private final Map<String, Set<String>> indexedModifications = new HashMap<>();
    private final Set<String> findingIds = new HashSet<>();
    public RiskRules(ScanOptions options, Report report) { this.options = options; this.report = report; for (Artifact a : report.artifacts) artifacts.put(a.hash, a); }
    public void evaluate() {
        for (Modification m : report.modifications) {
            options.checkBudget();
            if (m.referenceOnly || m.applicability == Applicability.INELIGIBLE) continue;
            for (Target t : m.targets) if (t.key != null && t.existence != Existence.MISSING) {
                String key = key(t.key);
                List<Change> bucket = index.computeIfAbsent(key, k -> new ArrayList<>());
                if (indexedModifications.computeIfAbsent(key, k -> new HashSet<>()).add(m.id)) bucket.add(new Change(m, t));
                report.targetIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(m.id);
                for (String mod : modIds(m)) report.modIndex.computeIfAbsent(mod, k -> new ArrayList<>()).add(key);
            }
        }
        long compared = 0, skipped = 0;
        for (List<Change> bucket : index.values()) {
            options.checkBudget();
            bucket.sort(Comparator.comparing(c -> c.modification.id));
            long count = (long) bucket.size() * (bucket.size() - 1) / 2;
            long allowance = Math.max(0L, (long) options.maxPairs - compared);
            if (count > allowance) skipped += count - allowance;
            long inspected = 0;
            outer: for (int i = 0; i < bucket.size(); i++) for (int j = i + 1; j < bucket.size(); j++) {
                if (inspected++ >= allowance) break outer;
                if (inspected % 1024 == 0) options.checkBudget();
                compared++;
                Change a = bucket.get(i), b = bucket.get(j);
                Set<String> sides = new TreeSet<>(a.modification.sides); sides.retainAll(b.modification.sides); sides.retainAll(Util.sides(options.side));
                if (sides.isEmpty()) continue;
                pair(a, b, sides);
            }
        }
        report.coverage.skippedPairs = (int) Math.min(Integer.MAX_VALUE, skipped);
        if (skipped > 0) report.diagnostic("PAIR_LIMIT", "rules", skipped + " candidate pairs skipped after limit " + options.maxPairs, false);
        report.targetIndex.replaceAll((k, v) -> v.stream().distinct().sorted().toList());
        report.modIndex.replaceAll((k, v) -> v.stream().distinct().sorted().toList());
        report.findings.sort(Comparator.comparing((Finding f) -> f.target.display()).thenComparing(f -> f.rule).thenComparing(f -> f.id));
    }
    private void pair(Change a, Change b, Set<String> sides) {
        Modification left = a.modification, right = b.modification;
        boolean resolved = a.target.resolution == Resolution.RESOLVED && b.target.resolution == Resolution.RESOLVED;
        finding("MS001", Severity.LOW, a, b, sides, "Multiple modifications declare the same target; overlap alone does not establish incompatibility.");
        // Incomplete/ambiguous selectors are candidate inventory, not strong replacement claims.
        if (!resolved) return;
        if (left.kind.equals("Overwrite") && right.kind.equals("Overwrite"))
            finding("MS002", Severity.HIGH, a, b, sides, "Multiple whole-method overwrites target the same method.");
        else if (left.kind.equals("Overwrite") || right.kind.equals("Overwrite"))
            finding("MS004", Severity.HIGH, a, b, sides, "A whole-method overwrite may replace injection points or behavior expected by another modification.");
        if (left.kind.equals("Redirect") && right.kind.equals("Redirect") && samePoint(a, b))
            finding("MS003", Severity.HIGH, a, b, sides, "Multiple redirects select the same instruction or the same static injection-point candidate.");
        if (cancellable(left) || cancellable(right))
            finding("MS005", Severity.MEDIUM, a, b, sides, "A cancellable callback may bypass behavior expected by another modification; actual cancellation is unverified.");
        if (left.kind.startsWith("Modify") && right.kind.startsWith("Modify") && samePoint(a, b) && sameOperand(left, right))
            finding("MS006", Severity.MEDIUM, a, b, sides, "Multiple value modifications may affect the same argument, constant, or variable; behavior may depend on order.");
        if (extras(left) && extras(right))
            finding("MS007", Severity.LOW, a, b, sides, "MixinExtras modifications can compose or chain; shared targets alone do not prove a conflict.");
        if ((extras(left) && right.kind.equals("Redirect") || extras(right) && left.kind.equals("Redirect")) && samePoint(a, b))
            finding("MS008", Severity.MEDIUM, a, b, sides, "A MixinExtras modification and redirect share an operation candidate; applicability and ordering need runtime verification.");
    }
    private static boolean cancellable(Modification m) { return m.kind.equals("Inject") && Boolean.TRUE.equals(m.options.get("cancellable")); }
    private static boolean extras(Modification m) { return Set.of("WrapOperation", "WrapMethod", "ModifyExpressionValue", "ModifyReturnValue", "WrapWithCondition").contains(m.kind); }
    private static boolean sameOperand(Modification a, Modification b) {
        if ((a.kind.equals("ModifyArgs") || b.kind.equals("ModifyArgs")) && (a.kind.equals("ModifyArg") || b.kind.equals("ModifyArg"))) return true;
        if (!a.kind.equals(b.kind)) return false;
        for (String key : List.of("index", "ordinal", "argsOnly", "constant")) if (!Objects.equals(a.options.get(key), b.options.get(key))) return false;
        return true;
    }
    private static boolean samePoint(Change a, Change b) {
        if (a.target.instructionsVerified && b.target.instructionsVerified)
            return !Collections.disjoint(new HashSet<>(a.target.instructions), new HashSet<>(b.target.instructions));
        if (a.modification.at.isEmpty() || b.modification.at.isEmpty()) return false;
        // Matching slice IDs alone cannot establish matching boundaries in JAR-only mode.
        if (!Objects.equals(a.modification.options.get("slice"), b.modification.options.get("slice"))) return false;
        for (Map<String, Object> x : a.modification.at) for (Map<String, Object> y : b.modification.at)
            if (normalizedAt(x).equals(normalizedAt(y))) return true;
        return false;
    }
    private static Map<String, Object> normalizedAt(Map<String, Object> source) {
        Map<String, Object> result = new TreeMap<>(source); result.remove("annotationType");
        result.putIfAbsent("ordinal", -1); result.putIfAbsent("opcode", -1); result.putIfAbsent("shift", "NONE");
        result.putIfAbsent("by", 0); result.putIfAbsent("slice", ""); result.putIfAbsent("target", ""); result.putIfAbsent("args", List.of());
        return result;
    }
    private void finding(String rule, Severity severity, Change a, Change b, Set<String> sides, String reason) {
        Finding f = new Finding(); f.rule = rule; f.severity = severity; f.reason = reason; f.target = a.target.key;
        f.category = a.modification.artifact.equals(b.modification.artifact) ? "WITHIN_ARTIFACT" :
                !Collections.disjoint(modIds(a.modification), modIds(b.modification)) ? "ALTERNATIVE_MOD_CANDIDATES" : "BETWEEN_MODS";
        f.modifications = List.of(a.modification.id, b.modification.id).stream().sorted().toList();
        f.mods = java.util.stream.Stream.concat(modIds(a.modification).stream(), modIds(b.modification).stream()).distinct().sorted().toList();
        f.scenarios = sides;
        boolean resolved = a.target.resolution == Resolution.RESOLVED && b.target.resolution == Resolution.RESOLVED;
        boolean conditional = a.modification.applicability != Applicability.ELIGIBLE || b.modification.applicability != Applicability.ELIGIBLE;
        f.confidence = !resolved || conditional || f.target.namespace().equals("unknown") ? Confidence.LOW :
                rule.equals("MS003") || rule.equals("MS006") || rule.equals("MS008") ?
                        a.target.instructionsVerified && b.target.instructionsVerified ? Confidence.HIGH : Confidence.MEDIUM : Confidence.HIGH;
        for (Change change : List.of(a, b)) {
            Modification m = change.modification;
            f.evidence.add(m.mixin + "." + m.handler + m.handlerDescriptor + " @" + m.kind + " selectors=" + m.selectors + " defaults=" + m.defaults + " at=" + m.at);
            f.limitations.add(change.target.limitation);
        }
        if (conditional) f.limitations.add("Plugin, nested candidate, or unresolved loader selection may prevent simultaneous application.");
        if (f.target.namespace().equals("unknown")) f.limitations.add("Input namespace is unknown; only raw selector overlap is compared.");
        if (!a.target.instructionsVerified || !b.target.instructionsVerified) f.limitations.add("Instruction identity is not verified for both modifications.");
        f.limitations = f.limitations.stream().distinct().sorted().toList();
        List<String> identities = List.of(identity(a.modification), identity(b.modification)).stream().sorted().toList();
        f.id = Util.id(rule, key(f.target), identities.get(0), identities.get(1));
        if (findingIds.add(f.id)) report.findings.add(f);
    }
    private List<String> modIds(Modification m) {
        Artifact artifact = artifacts.get(m.artifact);
        return artifact.mods.size() == 1 ? List.of(artifact.mods.getFirst().id()) : List.of("artifact:" + artifact.hash.substring(0, 12));
    }
    private String identity(Modification m) { return String.join(",", modIds(m)) + ":" + m.mixin + "." + m.handler + m.handlerDescriptor + ":" + m.kind; }
    public static String key(TargetKey k) { return k.gameVersion() + "|" + k.loaderProfile() + "|" + k.namespace() + "|" + k.display(); }
}
