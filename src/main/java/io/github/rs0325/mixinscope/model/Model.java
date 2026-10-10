package io.github.rs0325.mixinscope.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.*;

/** Report DTOs; raw declarations never disappear when resolution fails. */
public final class Model {
    private Model() {}
    public enum Severity { LOW, MEDIUM, HIGH }
    public enum Confidence { LOW, MEDIUM, HIGH }
    public enum Resolution { RESOLVED, AMBIGUOUS, UNRESOLVED }
    public enum Existence { UNVERIFIED, VERIFIED, MISSING }
    public enum Applicability { ELIGIBLE, INELIGIBLE, CONDITIONAL, UNKNOWN }
    public record Diagnostic(String code, String source, String message, boolean error) {}
    public record ModIdentity(String id, String version, String name) {}
    public record Transformer(String artifact, String kind, String resource, String limitation) {}
    public record TargetKey(String gameVersion, String loaderProfile, String namespace,
                            String owner, String name, String descriptor) {
        public String display() { return owner + "." + name + descriptor; }
    }
    public static final class Artifact {
        public String hash;
        public long size;
        public String loader = "unknown";
        public List<String> origins = new ArrayList<>();
        public List<ModIdentity> mods = new ArrayList<>();
        public Map<String, Object> metadata = new TreeMap<>();
        public boolean nested;
        public boolean partial;
        @JsonIgnore public Map<String, byte[]> entries = new TreeMap<>();
    }
    public static final class MixinConfig {
        public String artifact, path, packageName = "", plugin = "", refmap = "";
        public Set<String> sides = new TreeSet<>(List.of("client", "server"));
        public Applicability applicability = Applicability.ELIGIBLE;
        public Map<String, Object> raw = new TreeMap<>();
        public Map<String, Object> registration = new TreeMap<>();
        public Map<String, Object> references = new TreeMap<>();
        public List<String> requiredMods = new ArrayList<>();
    }
    public static final class Modification {
        public String id, artifact, config, mixin, handler, handlerDescriptor, kind, annotationDescriptor;
        public List<String> owners = new ArrayList<>();
        public List<String> selectors = new ArrayList<>();
        public Set<String> sides = new TreeSet<>();
        public Applicability applicability;
        public Map<String, Object> options = new TreeMap<>();
        public Map<String, Object> defaults = new TreeMap<>();
        public Map<String, Object> handlerAnalysis = new TreeMap<>();
        public List<Map<String, Object>> at = new ArrayList<>();
        public List<Map<String, Object>> normalizedAt = new ArrayList<>(), normalizedSlices = new ArrayList<>();
        public boolean pointCandidatesNormalized;
        public List<Target> targets = new ArrayList<>();
        public boolean referenceOnly;
    }
    public static final class Target {
        public String rawOwner, rawSelector, normalizedSelector;
        public TargetKey key;
        public Resolution resolution = Resolution.UNRESOLVED;
        public Existence existence = Existence.UNVERIFIED;
        public List<String> candidates = new ArrayList<>();
        public List<TargetKey> candidateKeys = new ArrayList<>();
        public String candidateSource = "NONE", declaringOwner;
        public boolean candidatesTruncated;
        public List<Integer> instructions = new ArrayList<>();
        public boolean instructionsVerified;
        public String bytecodeHash;
        public String limitation = "Target existence and runtime application have not been verified.";
    }
    public static final class Finding {
        public String id, rule, ruleVersion = "1", category, reason;
        public TargetKey target;
        public Severity severity;
        public Confidence confidence;
        public List<String> modifications = new ArrayList<>();
        public List<String> mods = new ArrayList<>();
        public Set<String> scenarios = new TreeSet<>();
        public List<String> evidence = new ArrayList<>();
        public List<String> limitations = new ArrayList<>();
        public boolean suppressed;
        public String suppressionReason;
    }
    public static final class Coverage {
        public int artifacts, failedArtifacts, modifications, unresolvedTargets, ambiguousTargets,
                   unknownApplicability, skippedPairs, suppressedFindings;
        public boolean partial;
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }
    public static final class TargetGroup {
        public String id;
        public TargetKey target;
        public Severity maxSeverity, activeMaxSeverity;
        public Confidence maxSeverityConfidence;
        public List<String> findingIds = new ArrayList<>(), rules = new ArrayList<>(), mods = new ArrayList<>(), modifications = new ArrayList<>();
        public Set<String> scenarios = new TreeSet<>();
        public int suppressedFindings;
    }
    public static final class Report {
        public String schemaVersion = "1.1", toolVersion = "0.1.0-SNAPSHOT", rulesetVersion = "2";
        public Map<String, Object> environment = new TreeMap<>();
        public List<String> assumptions = new ArrayList<>();
        public List<Artifact> artifacts = new ArrayList<>();
        public List<MixinConfig> configs = new ArrayList<>();
        public List<Modification> modifications = new ArrayList<>();
        public List<Transformer> transformers = new ArrayList<>();
        public List<Finding> findings = new ArrayList<>();
        public List<TargetGroup> targetGroups = new ArrayList<>();
        public Map<String, List<String>> targetIndex = new TreeMap<>();
        public Map<String, List<String>> modIndex = new TreeMap<>();
        public Map<String, Object> baseline = new TreeMap<>();
        public Map<String, String> displayFilter = new TreeMap<>();
        public List<String> visibleFindingIds = new ArrayList<>();
        public Coverage coverage = new Coverage();
        public void diagnostic(String code, String source, String message, boolean error) {
            coverage.diagnostics.add(new Diagnostic(code, source, message, error));
            if (error) coverage.partial = true;
        }
    }
}
