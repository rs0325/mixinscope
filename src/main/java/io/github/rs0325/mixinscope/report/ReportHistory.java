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
        if(document.size()!=1||!(document.get("suppressions") instanceof List<?>))throw new IOException("Expected a suppressions array and no unknown top-level fields");
        List<Map<String,Object>> validated=new ArrayList<>();List<LocalDate> expiries=new ArrayList<>();
        for (Object value : Util.list(document.get("suppressions"))) {
            Map<String, Object> rule = Util.map(value); String reason = Util.str(rule.get("reason"), "");
            if(!Set.of("rule","target","mod","reason","expires").containsAll(rule.keySet()))throw new IOException("Unknown suppression field");
            if (reason.isBlank()) throw new IOException("Suppression reason is required");
            if (!rule.containsKey("rule") && !rule.containsKey("target") && !rule.containsKey("mod")) throw new IOException("Suppression requires rule, target, or mod");
            for(String selector:List.of("rule","target","mod"))if(rule.containsKey(selector)&&(!(rule.get(selector) instanceof String text)||text.isBlank()))throw new IOException("Suppression selector must be a nonblank string: "+selector);
            LocalDate expires=null;
            if (rule.containsKey("expires")) {
                if(!(rule.get("expires") instanceof String text))throw new IOException("Invalid suppression expiry");
                try { expires = LocalDate.parse(text); } catch (DateTimeException ex) { throw new IOException("Invalid suppression expiry", ex); }
            }
            validated.add(rule);expiries.add(expires);
        }
        // Validate the entire document before applying anything; a typo must not broaden suppression.
        for(int i=0;i<validated.size();i++) {
            Map<String,Object> rule=validated.get(i);String reason=(String)rule.get("reason");LocalDate expires=expiries.get(i);
            if(expires!=null&&expires.isBefore(today)){report.diagnostic("SUPPRESSION_EXPIRED",file.getFileName().toString(),"Expired suppression: "+reason,false);continue;}
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
        var node=Util.JSON.readTree(readBytes(file));validateBaseline(node);
        Report previous = Util.JSON.treeToValue(node, Report.class);
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
        return Util.readBytes(file,32L<<20,()->{});
    }
    private static void validateBaseline(com.fasterxml.jackson.databind.JsonNode node)throws IOException {
        if(node==null||!node.isObject())throw new IOException("Expected a baseline report object");
        for(String name:List.of("schemaVersion","rulesetVersion"))if(!node.path(name).isTextual()||node.path(name).asText().isBlank())throw new IOException("Missing baseline header: "+name);
        for(String name:List.of("environment","coverage"))if(!node.path(name).isObject())throw new IOException("Missing baseline object: "+name);
        for(String name:List.of("artifacts","findings","transformers","modifications"))if(!node.path(name).isArray())throw new IOException("Missing baseline array: "+name);
        var coverage=node.path("coverage");
        if(!coverage.path("partial").isBoolean()||!coverage.path("diagnostics").isArray())throw new IOException("Invalid baseline coverage");
        for(String name:List.of("unresolvedTargets","ambiguousTargets","unknownApplicability","skippedPairs"))
            if(!coverage.path(name).isIntegralNumber()||!coverage.path(name).canConvertToInt()||coverage.path(name).asInt()<0)throw new IOException("Invalid baseline coverage counter: "+name);
        if(coverage.has("skippedTargets")&&(!coverage.path("skippedTargets").isIntegralNumber()||!coverage.path("skippedTargets").canConvertToInt()||coverage.path("skippedTargets").asInt()<0))throw new IOException("Invalid baseline skippedTargets");
        for(var artifact:node.path("artifacts"))if(!artifact.path("hash").isTextual()||artifact.path("hash").asText().isBlank())throw new IOException("Invalid baseline artifact hash");
        Set<String> ids=new HashSet<>();
        for(var finding:node.path("findings")) {
            if(!finding.path("id").isTextual()||finding.path("id").asText().isBlank()||!ids.add(finding.path("id").asText()))throw new IOException("Missing or duplicate baseline finding ID");
            if(!finding.path("target").isObject())throw new IOException("Missing baseline finding target");
            for(String name:List.of("owner","name","descriptor","namespace","gameVersion","loaderProfile"))if(!finding.path("target").path(name).isTextual())throw new IOException("Invalid baseline target: "+name);
        }
    }
}
