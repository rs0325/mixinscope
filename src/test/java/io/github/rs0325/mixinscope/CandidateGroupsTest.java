package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.*;
import io.github.rs0325.mixinscope.rules.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateGroupsTest {
    @TempDir Path directory;
    @Test void namedSelectorCanOverlapKnownDeclarationsWithoutBecomingResolved() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Inject","attack")),mod(directory,"b",new Method("Overwrite","attack()V")),mod(directory,"c",new Method("Overwrite","attack(I)V"))));
        Target t=r.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst();
        assertEquals(List.of("game/Player.attack()V","game/Player.attack(I)V"),t.candidates);assertEquals("MOD_DECLARATIONS",t.candidateSource);
        assertEquals(Resolution.AMBIGUOUS,t.resolution);assertEquals(Existence.UNVERIFIED,t.existence);assertNull(t.declaringOwner);
        assertEquals(2,r.findings.size());assertEquals(Set.of("MS001"),rules(r));assertTrue(r.findings.stream().allMatch(f->f.confidence==Confidence.LOW));
    }
    @Test void oneModDeclarationCannotResolveOmittedDescriptor() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Inject","attack")),mod(directory,"b",new Method("Overwrite","attack()V"))));
        Target t=r.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst();
        assertEquals(1,t.candidateKeys.size());assertEquals(Resolution.AMBIGUOUS,t.resolution);assertFalse(rules(r).contains("MS004"));
    }
    @Test void wildcardsRespectOwnerAndDescriptor() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Inject","att*()V")),mod(directory,"b",new Method("Overwrite","attack()V")),
                mod(directory,"c",new Method("Overwrite","attack(I)V")),mod(directory,"d","game/Other","*",new Method("Overwrite","attack()V"))));
        Finding f=r.findings.getFirst();assertEquals(1,r.findings.size());assertEquals(List.of("a","b"),f.mods);assertEquals("()V",f.target.descriptor());
    }
    @Test void declaredOwnersAreNotMergedWithoutAReferenceHierarchy() throws Exception {
        Report r=scan(options(mod(directory,"a","game/Child","*",new Method("Inject","attack")),mod(directory,"b","game/Parent","*",new Method("Overwrite","attack()V"))));
        assertTrue(r.findings.isEmpty());assertTrue(r.modifications.stream().allMatch(m->m.targets.getFirst().declaringOwner==null));
    }
    @Test void sameNamedMethodInDifferentLoadersIsNotADeclarationCandidate() throws Exception {
        Path forge=jar(directory.resolve("forge.jar"),Map.of("META-INF/mods.toml","modLoader=\"javafml\"\n[[mods]]\nmodId=\"forge_test\"\nversion=\"1\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "META-INF/MANIFEST.MF","Manifest-Version: 1.0\r\nMixinConfigs: test.mixins.json\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "test.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",mixin("game/Player",new Method("Overwrite","attack()V"))));
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack")),forge);o.loader="auto";Report r=scan(o);
        assertTrue(r.findings.isEmpty());assertTrue(r.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst().candidateKeys.isEmpty());
    }
    @Test void declarationCandidateLimitIsVisibleAndDoesNotProduceStrongRules() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack")),mod(directory,"b",new Method("Overwrite","attack()V")),mod(directory,"c",new Method("Overwrite","attack(I)V")));o.maxSelectorCandidates=1;
        Report r=scan(o);Target t=r.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst();
        assertTrue(t.candidatesTruncated);assertEquals(1,t.candidateKeys.size());assertTrue(diagnostic(r,"SELECTOR_CANDIDATE_LIMIT"));assertEquals(Set.of("MS001"),rules(r));
    }
    @Test void referenceCandidateLimitDoesNotResolveAnOverload() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack")));o.gameJar=game(directory);o.maxSelectorCandidates=1;
        Target t=scan(o).modifications.getFirst().targets.getFirst();assertTrue(t.candidatesTruncated);assertEquals(Resolution.AMBIGUOUS,t.resolution);assertEquals("REFERENCE_BYTECODE",t.candidateSource);
    }
    @Test void ineligibleAndMissingTargetsStillHaveAnInventory() throws Exception {
        ScanOptions o=options(mod(directory,"a","game/Player","client",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Inject","missing()V")));o.side="server";o.gameJar=game(directory);
        Report r=scan(o);assertTrue(r.findings.isEmpty());assertEquals(Set.of("a","b"),r.modIndex.keySet());assertEquals(2,r.targetIndex.size());
    }
    @Test void targetGroupsPreserveIndividualRulesAndSuppressionRisk() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V")),mod(directory,"c",new Method("Inject","getAttributeValue()D"))));
        TargetGroup g=r.targetGroups.getFirst();assertEquals(1,r.targetGroups.size());assertEquals(List.of("MS001","MS002"),g.rules);assertEquals(2,g.findingIds.size());assertEquals(Severity.HIGH,g.maxSeverity);
        Path file=directory.resolve("suppression.json");Files.writeString(file,"{\"suppressions\":[{\"rule\":\"MS002\",\"reason\":\"Verified test pair\"}]}");
        ReportHistory.suppress(r,file,LocalDate.of(2026,10,10));g=r.targetGroups.getFirst();assertEquals(Severity.HIGH,g.maxSeverity);assertEquals(Severity.LOW,g.activeMaxSeverity);assertEquals(1,g.suppressedFindings);
        String text=Reports.text(r,r.findings);assertEquals(1,text.split("Potential Conflict Detected",-1).length-1);assertTrue(text.contains("Rule: MS001"));assertTrue(text.contains("Rule: MS002"));assertTrue(text.contains("Active risk: LOW"));
    }
    @Test void groupIdentityIgnoresInputOrderingAndStaysProfileSpecific() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V"))));
        String id=r.targetGroups.getFirst().id;Collections.reverse(r.findings);FindingGroups.refresh(r);assertEquals(id,r.targetGroups.getFirst().id);
        Finding copy=Util.JSON.readValue(Util.JSON.writeValueAsString(r.findings.getFirst()),Finding.class);copy.id=Util.id("different-profile");copy.target=new TargetKey("other",copy.target.loaderProfile(),copy.target.namespace(),copy.target.owner(),copy.target.name(),copy.target.descriptor());r.findings.add(copy);
        FindingGroups.refresh(r);assertEquals(2,r.targetGroups.size());assertEquals(2,r.targetGroups.stream().map(g->g.id).distinct().count());
    }
    @Test void oldSchemaBaselineReturnsUnknownDisappearanceRatherThanErrorOrResolution() throws Exception {
        Report old=scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V"))));old.schemaVersion="1.0";
        var document=Util.JSON.valueToTree(old);((com.fasterxml.jackson.databind.node.ObjectNode)document).remove("targetGroups");
        for(var modification:document.get("modifications")) {
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)modification;object.remove(List.of("annotationDescriptor","handlerAnalysis"));
            for(var target:modification.get("targets"))((com.fasterxml.jackson.databind.node.ObjectNode)target).remove(List.of("candidateKeys","candidateSource","candidatesTruncated","declaringOwner"));
        }
        Path path=directory.resolve("old.json");Files.writeString(path,Util.JSON.writeValueAsString(document));Report now=new Report();now.environment=old.environment;ReportHistory.baseline(now,path);
        assertEquals(true,now.baseline.get("schemaChanged"));assertEquals(false,now.baseline.get("comparable"));assertTrue(Util.list(now.baseline.get("resolved")).isEmpty());assertEquals(2,Util.list(now.baseline.get("unverifiedDisappearance")).size());
    }
}
