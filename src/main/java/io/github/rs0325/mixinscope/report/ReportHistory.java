package io.github.rs0325.mixinscope.report;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.rules.FindingGroups;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class ReportHistory {
    private ReportHistory() {}
    public static void suppress(Report report, Path file, LocalDate today) throws IOException {
        Map<String, Object> document = read(file);
        for (Object value : Util.list(document.get("suppressions"))) {
            Map<String, Object> rule = Util.map(value); String reason = Util.str(rule.get("reason"), "");
            if (reason.isBlank()) throw new IOException("Suppression reason is required");
            if (!rule.containsKey("rule") && !rule.containsKey("target") && !rule.containsKey("mod")) throw new IOException("Suppression requires rule, target, or mod");
            if (rule.containsKey("expires")) {
                LocalDate expires;
                try { expires = LocalDate.parse(rule.get("expires").toString()); } catch (DateTimeException ex) { throw new IOException("Invalid suppression expiry", ex); }
                if (expires.isBefore(today)) { report.diagnostic("SUPPRESSION_EXPIRED", file.getFileName().toString(), "Expired suppression: " + reason, false); continue; }
            }
            for (Finding finding : report.findings) if (
                    (!rule.containsKey("rule") || finding.rule.equals(rule.get("rule"))) &&
                    (!rule.containsKey("target") || finding.target.display().equals(rule.get("target"))) &&
                    (!rule.containsKey("mod") || finding.mods.contains(rule.get("mod")))) {
                finding.suppressed = true; finding.suppressionReason = reason;
            }
        }
        report.coverage.suppressedFindings = (int) report.findings.stream().filter(f -> f.suppressed).count();
        FindingGroups.refresh(report);
    }
    public static void baseline(Report report, Path file) throws IOException {
        Report previous = Util.JSON.treeToValue(Util.JSON.readTree(readBytes(file)), Report.class);
        if (!Set.of("1.0", "1.1").contains(previous.schemaVersion)) throw new IOException("Unsupported baseline schema version");
        Map<String, Finding> before = new TreeMap<>(), now = new TreeMap<>();
        for (Finding f : previous.findings) before.put(f.id, f);
        for (Finding f : report.findings) now.put(f.id, f);
        List<String> fresh = now.keySet().stream().filter(id -> !before.containsKey(id)).toList();
        List<String> unchanged = now.keySet().stream().filter(before::containsKey).toList();
        List<String> disappeared = before.keySet().stream().filter(id -> !now.containsKey(id)).toList();
        // Missing findings cannot be called resolved when analysis or semantics are incomparable.
        boolean comparable = report.schemaVersion.equals(previous.schemaVersion) && !report.coverage.partial && !previous.coverage.partial &&
                report.environment.equals(previous.environment) && report.rulesetVersion.equals(previous.rulesetVersion) &&
                noGaps(report) && noGaps(previous);
        report.baseline.put("new", fresh); report.baseline.put("unchanged", unchanged);
        report.baseline.put("resolved", comparable ? disappeared : List.of());
        report.baseline.put("unverifiedDisappearance", comparable ? List.of() : disappeared);
        report.baseline.put("environmentChanged", !report.environment.equals(previous.environment));
        report.baseline.put("rulesetChanged", !report.rulesetVersion.equals(previous.rulesetVersion));
        report.baseline.put("schemaChanged", !report.schemaVersion.equals(previous.schemaVersion));
        List<String> oldHashes = previous.artifacts.stream().map(a -> a.hash).sorted().toList();
        List<String> hashes = report.artifacts.stream().map(a -> a.hash).sorted().toList();
        report.baseline.put("artifactsChanged", !oldHashes.equals(hashes));
        report.baseline.put("comparable", comparable);
    }
    private static boolean noGaps(Report report) {
        return report.coverage.unresolvedTargets == 0 && report.coverage.ambiguousTargets == 0 && report.coverage.skippedPairs == 0 && report.coverage.skippedTargets==0 &&
                report.coverage.unknownApplicability == 0 && report.transformers.isEmpty() && report.coverage.diagnostics.isEmpty();
    }
    private static Map<String, Object> read(Path file) throws IOException { return Util.json(readBytes(file)); }
    private static byte[] readBytes(Path file) throws IOException {
        if (Files.size(file) > 32L << 20) throw new IOException("History file size limit exceeded");
        return Files.readAllBytes(file);
    }
}
