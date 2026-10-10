package io.github.rs0325.mixinscope.rules;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import java.util.*;

/** Derived target summaries. Every rule and pair remains available as its original finding. */
public final class FindingGroups {
    private FindingGroups() {}
    public static void refresh(Report report) { report.targetGroups = summarize(report.findings); }
    public static List<TargetGroup> summarize(Collection<Finding> findings) {
        Map<String, List<Finding>> buckets = new TreeMap<>();
        for (Finding finding : findings) buckets.computeIfAbsent(RiskRules.key(finding.target), k -> new ArrayList<>()).add(finding);
        List<TargetGroup> result = new ArrayList<>();
        for (var bucket : buckets.entrySet()) {
            List<Finding> members = bucket.getValue(); TargetGroup group = new TargetGroup();
            group.id = Util.id("target-group", bucket.getKey()); group.target = members.getFirst().target;
            group.maxSeverity = members.stream().map(f -> f.severity).max(Comparator.naturalOrder()).orElseThrow();
            group.activeMaxSeverity = members.stream().filter(f -> !f.suppressed).map(f -> f.severity).max(Comparator.naturalOrder()).orElse(null);
            group.maxSeverityConfidence = members.stream().filter(f -> f.severity == group.maxSeverity).map(f -> f.confidence).min(Comparator.naturalOrder()).orElseThrow();
            group.findingIds = members.stream().map(f -> f.id).distinct().sorted().toList();
            group.rules = members.stream().map(f -> f.rule).distinct().sorted().toList();
            group.mods = members.stream().flatMap(f -> f.mods.stream()).distinct().sorted().toList();
            group.modifications = members.stream().flatMap(f -> f.modifications.stream()).distinct().sorted().toList();
            members.forEach(f -> group.scenarios.addAll(f.scenarios));
            group.suppressedFindings = (int) members.stream().filter(f -> f.suppressed).count();
            result.add(group);
        }
        return result;
    }
}
