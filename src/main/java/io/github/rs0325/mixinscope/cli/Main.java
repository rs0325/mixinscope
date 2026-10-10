package io.github.rs0325.mixinscope.cli;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "mixinscope", mixinStandardHelpOptions = true, versionProvider = io.github.rs0325.mixinscope.BuildVersion.class,
        description = "Inspect Minecraft mod JARs without executing them.", subcommands = Main.Scan.class)
public final class Main implements Runnable {
    @Spec CommandLine.Model.CommandSpec spec;
    public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
    public static CommandLine commandLine() {
        return new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> { cmd.getErr().println("ERROR: " + ex.getMessage()); return 2; });
    }
    public static void main(String[] args) { System.exit(commandLine().execute(args)); }
    @Command(name = "scan", mixinStandardHelpOptions = true, description = "Report Mixin targets and potential compatibility risks.")
    public static final class Scan implements Callable<Integer> {
        @Spec CommandLine.Model.CommandSpec spec;
        @Parameters(arity = "1..*", paramLabel = "JAR_OR_DIRECTORY") List<Path> inputs;
        @Option(names="--loader", defaultValue="auto") String loader;
        @Option(names="--loader-version", defaultValue="unknown") String loaderVersion;
        @Option(names="--metadata-profile", defaultValue="auto", description="auto or fml-12.0 (explicit requiredMods/behaviorVersion profile).") String metadataProfile;
        @Option(names="--minecraft-version", defaultValue="unknown") String gameVersion;
        @Option(names="--mixin-version", defaultValue="unknown") String mixinVersion;
        @Option(names="--mixinextras-version", defaultValue="unknown", description="Declared MixinExtras version; verified profiles: 0.3.2, 0.4.1, 0.5.0.") String mixinExtrasVersion;
        @Option(names="--side", defaultValue="both", description="Physical client, dedicated server (server), or both scenarios.") String side;
        @Option(names="--input-namespace", defaultValue="unknown") String namespace;
        @Option(names="--output-namespace") String outputNamespace;
        @Option(names="--game-namespace") String gameNamespace;
        @Option(names="--game-jar") Path gameJar;
        @Option(names="--classpath", split=",", description="Comma-separated reference JARs.") List<Path> classpath = new ArrayList<>();
        @Option(names="--mappings") Path mappings;
        @Option(names="--mapping-game-version") String mappingGameVersion;
        @Option(names="--refmap-context") String refmapContext;
        @Option(names="--target-java", defaultValue="21") int targetJava;
        @Option(names="--format", defaultValue="text") String format;
        @Option(names="--output") Path output;
        @Option(names="--mod") String mod;
        @Option(names="--target") String target;
        @Option(names="--min-severity") Severity minimum;
        @Option(names="--fail-on") Severity failOn;
        @Option(names="--strict") boolean strict;
        @Option(names="--suppressions") Path suppressions;
        @Option(names="--baseline") Path baseline;
        @Option(names="--date", description="Suppression evaluation date, YYYY-MM-DD; default is current UTC date.") String date;
        @Option(names="--max-pairs", defaultValue="100000") int maxPairs;
        @Option(names="--max-selector-candidates", defaultValue="1000") int maxSelectorCandidates;
        @Option(names="--max-targets", defaultValue="100000", description="Maximum owner/selector combinations to resolve.") int maxTargets;
        @Option(names="--max-report-mib", defaultValue="64", description="UTF-8 JSON payload budget (including HTML-safe escaping); also checked for text reports.") long maxReport;
        @Option(names="--max-entry-mib", defaultValue="16") long maxEntry;
        @Option(names="--max-total-mib", defaultValue="256") long maxTotal;
        @Option(names="--max-archive-mib", defaultValue="128") long maxArchive;
        @Option(names="--max-depth", defaultValue="4") int maxDepth;
        @Option(names="--max-entries", defaultValue="50000") int maxEntries;
        @Option(names="--max-ratio", defaultValue="200") int maxRatio;
        @Option(names="--timeout-seconds", defaultValue="120") long timeout;
        public Integer call() throws Exception {
            validate();
            ScanOptions options = new ScanOptions(); options.inputs = inputs; options.loader = loader; options.loaderVersion = loaderVersion;
            options.metadataProfile = metadataProfile;
            options.gameVersion = gameVersion; options.mixinVersion = mixinVersion; options.mixinExtrasVersion = mixinExtrasVersion; options.side = side; options.namespace = namespace;
            options.outputNamespace = outputNamespace; options.gameNamespace = gameNamespace; options.gameJar = gameJar; options.classpath = classpath;
            options.mappings = mappings; options.mappingGameVersion = mappingGameVersion; options.refmapContext = refmapContext;
            options.targetJava = targetJava; options.maxPairs = maxPairs; options.maxEntry = maxEntry << 20; options.maxTotal = maxTotal << 20;
            options.maxSelectorCandidates = maxSelectorCandidates;
            options.maxTargets=maxTargets;
            options.maxArchive = maxArchive << 20; options.maxDepth = maxDepth; options.maxEntries = maxEntries; options.maxRatio = maxRatio; options.timeoutSeconds = timeout;
            Report report = new io.github.rs0325.mixinscope.core.Scanner().scan(options);
            try {
                if (suppressions != null) ReportHistory.suppress(report, suppressions, date == null ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(date));
                if (baseline != null) ReportHistory.baseline(report, baseline);
            } catch (IOException | DateTimeException ex) { report.diagnostic("HISTORY_ERROR", "history", ex.getMessage(), true); }
            List<Finding> shown = report.findings.stream().filter(f -> (minimum == null || f.severity.compareTo(minimum) >= 0) &&
                    (mod == null || f.mods.contains(mod)) && (target == null || f.target.display().contains(target))).toList();
            report.displayFilter.putAll(Map.of("mod", mod == null ? "" : mod, "target", target == null ? "" : target, "minSeverity", minimum == null ? "" : minimum.name()));
            report.visibleFindingIds = shown.stream().map(f -> f.id).toList();
            // JSON/HTML preserve the complete inventory for baselines and inspection.
            String content;
            try {
                content=switch(format){case "json"->Reports.json(report,maxReport<<20);case "html"->Reports.html(report,maxReport<<20);
                    default->{Reports.validatePayload(report,maxReport<<20);yield Reports.text(report,shown);}};
            }catch(IOException error){spec.commandLine().getErr().println("ERROR OUTPUT_ERROR: "+error.getMessage());return 2;}
            for (Diagnostic d : report.coverage.diagnostics) spec.commandLine().getErr().println((d.error() ? "ERROR " : "NOTE ") + d.code() + " " + d.source() + ": " + d.message());
            if (output == null) spec.commandLine().getOut().print(content);
            else {
                try {
                    if (output.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("Output must not be a JAR file");
                    List<Path> protectedInputs = new ArrayList<>(inputs); protectedInputs.addAll(classpath);
                    for (Path source : Arrays.asList(gameJar, mappings, suppressions, baseline)) if (source != null) protectedInputs.add(source);
                    for (Path input : protectedInputs) if (Files.isRegularFile(input) && Files.exists(output) && Files.isSameFile(input, output)) throw new IOException("Output must not overwrite an input file");
                    Path parent = output.toAbsolutePath().getParent(); Files.createDirectories(parent);
                    Path temporary = Files.createTempFile(parent, ".mixinscope-", ".tmp");
                    try { Files.writeString(temporary, content, StandardCharsets.UTF_8); Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
                    finally { Files.deleteIfExists(temporary); }
                } catch (IOException ex) { spec.commandLine().getErr().println("ERROR OUTPUT_ERROR: " + ex.getMessage()); return 2; }
            }
            if(output==null) {
                spec.commandLine().getOut().flush();
                if(spec.commandLine().getOut().checkError()){spec.commandLine().getErr().println("ERROR OUTPUT_ERROR: Cannot write standard output");return 2;}
            }
            if (report.coverage.partial || strict && !report.coverage.diagnostics.isEmpty()) return 2;
            return failOn != null && report.findings.stream().anyMatch(f -> !f.suppressed && f.severity.compareTo(failOn) >= 0) ? 1 : 0;
        }
        private void validate() {
            if (!Set.of("auto", "fabric", "forge", "neoforge", "quilt").contains(loader)) invalid("Unknown loader");
            if (!Set.of("client", "server", "both").contains(side)) invalid("Unknown physical side");
            if (!Set.of("text", "json", "html").contains(format)) invalid("Format must be text, json or html");
            if (!Set.of("auto", "fml-12.0").contains(metadataProfile)) invalid("Unknown metadata profile");
            if (maxPairs < 0 || maxReport<1 || maxReport>512 || maxTargets<1 || maxTargets>1_000_000 || maxSelectorCandidates < 1 || maxSelectorCandidates > 100_000 || maxDepth < 0 || maxEntries < 1 || maxRatio < 1 || targetJava < 8 || maxEntry < 1 || maxArchive < 1 || maxTotal < 1 ||
                    maxEntry > 4096 || maxArchive > 4096 || maxTotal > 8192 || timeout < 1 || timeout > 86400) invalid("Invalid resource limits");
        }
        private void invalid(String reason) { throw new ParameterException(spec.commandLine(), reason); }
    }
}
