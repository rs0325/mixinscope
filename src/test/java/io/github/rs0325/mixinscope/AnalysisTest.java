package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @TempDir Path directory;
    @Test void abcGroupsOnlyTheTwoAttackMods() throws Exception {
        Path a = mod(directory,"a",new Method("Overwrite","attack()V"));
        Path b = mod(directory,"b",new Method("Overwrite","attack()V"));
        Path c = mod(directory,"c",new Method("Overwrite","getAttributeValue()D"));
        Report report = scan(options(a,b,c));
        Finding f = report.findings.stream().filter(v->v.rule.equals("MS002")).findFirst().orElseThrow();
        assertEquals(List.of("a","b"), f.mods); assertEquals(Severity.HIGH,f.severity); assertEquals(Confidence.HIGH,f.confidence);
        assertEquals(2,report.targetIndex.size()); assertEquals(3,report.modIndex.size()); assertFalse(report.coverage.partial);
    }
    @Test void ordinaryInjectsAreNotHighRisk() throws Exception {
        Report report = scan(options(mod(directory,"a",new Method("Inject","attack()V")),mod(directory,"b",new Method("Inject","attack()V"))));
        assertEquals(Set.of("MS001"),rules(report)); assertEquals(Severity.LOW,report.findings.getFirst().severity);
    }
    @Test void overloadAndOtherOwnerAreSeparate() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack()V")), b=mod(directory,"b",new Method("Inject","attack(I)V"));
        Path c=mod(directory,"c","game/Other","*",new Method("Inject","attack()V"));
        assertTrue(scan(options(a,b,c)).findings.isEmpty());
    }
    @Test void physicalSidesDoNotCoexist() throws Exception {
        Report report=scan(options(mod(directory,"a","game/Player","client",new Method("Overwrite","attack()V")),mod(directory,"b","game/Player","server",new Method("Overwrite","attack()V"))));
        assertTrue(report.findings.isEmpty());
    }
    @Test void sameIdVersionsAreUnknownCandidates() throws Exception {
        Path a=mod(directory.resolve("first"),"a",new Method("Overwrite","attack()V"));
        Path b=customMod(directory.resolve("second"),"a",Map.of("schemaVersion",1,"id","a","version","2","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin")),"game/Player",new Method("Overwrite","attack()V"));
        Report report=scan(options(a,b)); assertTrue(diagnostic(report,"DUPLICATE_MOD_ID"));
        assertTrue(report.findings.stream().allMatch(f->f.confidence==Confidence.LOW));
        assertTrue(report.findings.stream().allMatch(f->f.category.equals("ALTERNATIVE_MOD_CANDIDATES")));
    }
    @Test void duplicateContentDoesNotCreateSelfConflict() throws Exception {
        Path a=mod(directory,"a",new Method("Overwrite","attack()V"));
        Path copy=directory.resolve("copy.jar"); Files.copy(a,copy);
        Report report=scan(options(a,copy)); assertEquals(1,report.artifacts.size()); assertEquals(2,report.artifacts.getFirst().origins.size()); assertTrue(report.findings.isEmpty());
    }
    @Test void cancellableMeansPossibleCancellation() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("Inject","attack()V",Map.of("cancellable",true))),mod(directory,"b",new Method("Inject","attack()V"))));
        assertTrue(rules(report).contains("MS005")); assertFalse(report.findings.stream().anyMatch(f->f.severity==Severity.HIGH));
    }
    @Test void overwriteAndInjectorReportHighCandidate() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Inject","attack()V"))));
        assertTrue(rules(report).contains("MS004"));
    }
    @Test void shadowAndAccessorDoNotCountAsBodyModifications() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("Shadow","attack()V")),mod(directory,"b",new Method("Accessor","attack()V"))));
        assertTrue(report.findings.isEmpty()); assertTrue(report.modifications.stream().allMatch(m->m.referenceOnly));
    }
    @Test void pluginIsNeverRunAndLowersConfidence() throws Exception {
        Path a=customMod(directory,"a",Map.of("schemaVersion",1,"id","a","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin"),"plugin","evil.ShouldNotRun"),"game/Player",new Method("Overwrite","attack()V"));
        Report report=scan(options(a,mod(directory,"b",new Method("Overwrite","attack()V"))));
        assertTrue(diagnostic(report,"PLUGIN_NOT_EXECUTED")); assertTrue(report.findings.stream().allMatch(f->f.confidence==Confidence.LOW));
    }
    @Test void visibleAndInvisibleAnnotationsAreBothRead() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("Inject","attack()V",Map.of(),true)),mod(directory,"b",new Method("Inject","attack()V"))));
        assertEquals(Set.of("MS001"),rules(report));
    }
    @Test void missingClassReturnsPartialReport() throws Exception {
        Path a=jar(directory.resolve("a.jar"),Map.of("fabric.mod.json",Util.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"id","a","mixins",List.of("a.mixins.json"))),"a.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("Absent")))));
        Report report=scan(options(a)); assertTrue(report.coverage.partial); assertTrue(diagnostic(report,"MIXIN_CLASS_MISSING"));
    }
    @Test void missingRefmapIsRetainedAsDiagnostic() throws Exception {
        Path a=customMod(directory,"a",Map.of("schemaVersion",1,"id","a","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin"),"refmap","absent.json"),"game/Player",new Method("Inject","attack()V"));
        assertTrue(diagnostic(scan(options(a)),"REFMAP_MISSING"));
    }
    @Test void descriptorOmittedStaysAmbiguousUntilReferenceIsProvided() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack"));
        Report report=scan(options(a)); assertEquals(Resolution.AMBIGUOUS,report.modifications.getFirst().targets.getFirst().resolution);
        ScanOptions o=options(a); o.gameJar=game(directory); report=scan(o);
        assertEquals(2,report.modifications.getFirst().targets.getFirst().candidates.size());
        assertEquals(Resolution.AMBIGUOUS,report.modifications.getFirst().targets.getFirst().resolution);
    }
    @Test void pairBudgetIsReported() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V")),mod(directory,"b",new Method("Inject","attack()V")),mod(directory,"c",new Method("Inject","attack()V")));
        o.maxPairs=1; Report report=scan(o); assertEquals(2,report.coverage.skippedPairs); assertTrue(diagnostic(report,"PAIR_LIMIT"));
    }
    @Test void wrappersAreNotTreatedAsCompetingRedirects() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("WrapOperation","attack()V")),mod(directory,"b",new Method("WrapOperation","attack()V"))));
        assertTrue(rules(report).contains("MS007")); assertFalse(rules(report).contains("MS003")); assertTrue(report.findings.stream().allMatch(f->f.severity==Severity.LOW));
    }
    @Test void badSelectorIsPreservedNotForcedIntoKnownTarget() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("Inject","@Custom(foo)"))));
        assertTrue(diagnostic(report,"SELECTOR_UNSUPPORTED")); assertEquals("@Custom(foo)",report.modifications.getFirst().targets.getFirst().rawSelector);
    }
}
