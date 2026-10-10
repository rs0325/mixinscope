package io.github.rs0325.mixinscope.core;

import io.github.rs0325.mixinscope.archive.ArchiveReader;
import io.github.rs0325.mixinscope.loader.Discovery;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.parser.MixinParser;
import io.github.rs0325.mixinscope.resolver.TargetResolver;
import io.github.rs0325.mixinscope.rules.RiskRules;
import io.github.rs0325.mixinscope.rules.FindingGroups;
import java.util.*;

public final class Scanner {
    public Report scan(ScanOptions options) {
        Report report = new Report();
        options.startBudget();
        report.environment.put("minecraftVersion", options.gameVersion); report.environment.put("loader", options.loader);
        report.environment.put("loaderVersion", options.loaderVersion); report.environment.put("mixinVersion", options.mixinVersion);
        report.environment.put("mixinExtrasVersion", options.mixinExtrasVersion);
        report.environment.put("metadataProfile", options.metadataProfile);
        if(options.metadataProfile.equals("fml-12.0"))report.assumptions.add("fml-12.0 explicitly declares FML source snapshot bcbdb3520249deba18d92de2fcbdcee550c75233 with sponge-mixin 0.17.1+mixin.0.8.7; installed/custom runtime dependencies are not inspected.");
        report.environment.put("inputNamespace", options.namespace); report.environment.put("side", options.side);
        report.environment.put("targetJava", options.targetJava);
        report.assumptions.add("Static analysis only: mods, Mixin plugins and transformers are never executed.");
        report.assumptions.add("Loader dependency selection and runtime configuration are not emulated.");
        if (options.namespace.equals("unknown")) report.diagnostic("NAMESPACE_UNKNOWN", "profile", "Use --input-namespace for stronger target identity", false);
        if (options.gameVersion.equals("unknown")) report.diagnostic("GAME_VERSION_UNKNOWN", "profile", "Game version is not declared; cross-version compatibility is unverified", false);
        try {
            ArchiveReader archives = new ArchiveReader(options, report);
            new Discovery(options, report, archives).discover(archives.roots(options.inputs));
            new MixinParser(options, report).parse();
            new TargetResolver(options, report).resolve();
            new RiskRules(options, report).evaluate();
        } catch (RuntimeException ex) { report.diagnostic("ANALYSIS_ERROR", "scan", String.valueOf(ex.getMessage()), true); }
        report.artifacts.sort(Comparator.comparing(a -> a.hash));
        for (Artifact a : report.artifacts) { a.origins = a.origins.stream().distinct().sorted().toList(); a.mods.sort(Comparator.comparing(ModIdentity::id)); }
        report.configs.sort(Comparator.comparing((MixinConfig c) -> c.artifact).thenComparing(c -> c.path).thenComparing(c -> c.sides.toString()));
        report.transformers.sort(Comparator.comparing(Transformer::artifact).thenComparing(Transformer::resource));
        report.coverage.diagnostics = new ArrayList<>(report.coverage.diagnostics.stream().distinct()
                .sorted(Comparator.comparing(Diagnostic::code).thenComparing(Diagnostic::source).thenComparing(Diagnostic::message)).toList());
        Coverage c = report.coverage; c.artifacts = report.artifacts.size(); c.failedArtifacts = (int) report.artifacts.stream().filter(a -> a.partial).count();
        c.modifications = report.modifications.size();
        for (Modification m : report.modifications) {
            if (m.applicability == Applicability.UNKNOWN || m.applicability == Applicability.CONDITIONAL) c.unknownApplicability++;
            if (m.referenceOnly) continue;
            if (m.targets.isEmpty()) c.unresolvedTargets++;
            for (Target t : m.targets) { if (t.resolution == Resolution.UNRESOLVED) c.unresolvedTargets++; if (t.resolution == Resolution.AMBIGUOUS) c.ambiguousTargets++; }
        }
        FindingGroups.refresh(report);
        return report;
    }
}
