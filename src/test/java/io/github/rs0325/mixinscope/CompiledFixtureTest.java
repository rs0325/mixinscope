package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads actual javac + upstream annotation output without loading any fixture Mod. */
class CompiledFixtureTest {
    @TempDir Path directory;
    private static final String ROOT="io/github/rs0325/mixinscope/fixture/";
    private byte[] compiled(String name) throws Exception {
        for (String path : System.getProperty("mixinscope.fixtureClasses").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path file=Path.of(path).resolve(ROOT+name+".class");if(Files.isRegularFile(file))return Files.readAllBytes(file);
        }
        throw new IllegalStateException("Missing compiled fixture: "+name);
    }
    private Report analyze(String target,String... mixins) throws Exception {
        List<Path> jars=new ArrayList<>();
        for(String name:mixins)jars.add(jar(directory.resolve(name+".jar"),Map.of(
                "fabric.mod.json",Util.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"id",name.toLowerCase(Locale.ROOT),"version","1","mixins",List.of("fixture.mixins.json"))),
                "fixture.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","io.github.rs0325.mixinscope.fixture.mixin","mixins",List.of(name))),
                ROOT+"mixin/"+name+".class",compiled("mixin/"+name))));
        Map<String,byte[]> reference=new TreeMap<>();reference.put(ROOT+"target/"+target+".class",compiled("target/"+target));
        if(target.endsWith("Child"))reference.put(ROOT+"target/ParentCalculator.class",compiled("target/ParentCalculator"));
        var o=options(jars.toArray(Path[]::new));o.gameJar=jar(directory.resolve("reference.jar"),reference);
        return scan(o);
    }
    @Test void inheritedInjectAndOverwriteNeverVerifyParentInstructions() throws Exception {
        for(String[] example:List.of(new String[]{"OptionalInheritedChild","OptionalInherited"},new String[]{"RequiredInheritedChild","RequiredInherited"},new String[]{"OverwriteInheritedChild","InheritedOverwrite"})) {
            Report report=analyze(example[0],example[1]);Target target=report.modifications.getFirst().targets.getFirst();
            assertEquals(ROOT+"target/"+example[0],target.key.owner());assertEquals(ROOT+"target/ParentCalculator",target.declaringOwner);
            assertEquals(Resolution.UNRESOLVED,target.resolution);assertEquals(Existence.UNVERIFIED,target.existence);assertFalse(target.instructionsVerified);
            assertTrue(target.candidateKeys.isEmpty());assertTrue(diagnostic(report,"TARGET_INHERITED_NOT_LOCAL"));
        }
    }
    @Test void ownOverrideResolvesWithoutAddingParentOverloads() throws Exception {
        Report report=analyze("OwnedOverrideChild","OwnedOverride");Target target=report.modifications.getFirst().targets.getFirst();
        assertEquals(Resolution.RESOLVED,target.resolution);assertEquals(Existence.VERIFIED,target.existence);assertEquals("(I)I",target.key.descriptor());
        assertEquals(ROOT+"target/OwnedOverrideChild",target.declaringOwner);assertTrue(target.instructionsVerified);assertEquals(1,target.candidateKeys.size());
    }
    @Test void qualifiedSelectorCannotRedirectMixinToAnotherClass() throws Exception {
        Report report=analyze("ExplicitOwnerCalculator","ExplicitOwner");Target target=report.modifications.getFirst().targets.getFirst();
        assertNull(target.key);assertFalse(target.instructionsVerified);assertTrue(diagnostic(report,"SELECTOR_UNSUPPORTED"));assertTrue(report.findings.isEmpty());
    }
    @Test void compiledOperationChainHasActualSignaturesAndInstructionEvidence() throws Exception {
        Report r=analyze("ChainCalculator","ChainA","ChainB");
        assertEquals(2,r.modifications.size());assertEquals(Set.of("MS001","MS007"),rules(r));
        assertTrue(r.findings.stream().allMatch(f->f.severity==Severity.LOW));
        for(var m:r.modifications){assertEquals("(ILcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)I",m.handlerDescriptor);
            assertEquals(1,m.handlerAnalysis.get("directOperationCallCount"));assertEquals("DIRECT_OPERATION_CALL_PRESENT",m.handlerAnalysis.get("operationCallEvidence"));
            assertTrue(m.targets.getFirst().instructionsVerified);assertEquals(List.of(1),m.targets.getFirst().instructions);}
        assertFalse(diagnostic(r,"INJECTION_POINT_UNSUPPORTED"));
    }
    @Test void directAbsenceCannotDistinguishBypassFromHelperDelegation() throws Exception {
        Report bypass=analyze("BypassCalculator","BypassA","BypassB"),delegated=analyze("DelegatedCalculator","Delegated");
        var absent=bypass.modifications.stream().filter(m->m.mixin.endsWith("BypassA")).findFirst().orElseThrow();
        assertEquals("NO_DIRECT_OPERATION_CALL",absent.handlerAnalysis.get("operationCallEvidence"));
        assertEquals("NO_DIRECT_OPERATION_CALL",delegated.modifications.getFirst().handlerAnalysis.get("operationCallEvidence"));
        assertTrue(diagnostic(delegated,"WRAPPER_CALL_UNVERIFIED"));assertTrue(bypass.findings.stream().allMatch(f->f.severity==Severity.LOW));
    }
    @Test void compiledWholeMethodWrappersRemainChainCandidates() throws Exception {
        Report r=analyze("MethodCalculator","MethodA","MethodB");
        assertEquals(Set.of("MS001","MS007"),rules(r));assertFalse(diagnostic(r,"INJECTION_POINT_MISSING"));
        assertTrue(r.modifications.stream().allMatch(m->m.kind.equals("WrapMethod")&&m.targets.getFirst().existence==Existence.VERIFIED));
    }
    @Test void compiledRedirectAndOverwriteControlsHaveHighRisk() throws Exception {
        for(String kind:List.of("Redirect","Overwrite")) {
            Report r=analyze(kind+"Calculator",kind+"A",kind+"B");
            Finding f=r.findings.stream().filter(x->x.rule.equals(kind.equals("Redirect")?"MS003":"MS002")).findFirst().orElseThrow();
            assertEquals(Severity.HIGH,f.severity);assertEquals(Confidence.HIGH,f.confidence);
        }
    }
    @Test void actualInitializerBytecodeIsCoverageRatherThanAnInventedInjector() throws Exception {
        Report r=analyze("InitializerCalculator","InitializerMixin");assertTrue(r.modifications.isEmpty());assertTrue(r.findings.isEmpty());
        assertTrue(diagnostic(r,"MIXIN_INITIALIZER_UNANALYZED"));assertEquals(2,r.transformers.stream().filter(t->t.kind().equals("MIXIN_INITIALIZER")).count());
        assertTrue(r.transformers.stream().anyMatch(t->t.resource().contains("<clinit>")));assertTrue(r.transformers.stream().anyMatch(t->t.resource().contains("<init>")));
    }
    @Test void validNonCancellableCallbacksStayLowAndExplicitArgumentIdentityIsVerified() throws Exception {
        Report safe=analyze("SafeCalculator","SafeA","SafeB");assertEquals(Set.of("MS001"),rules(safe));assertTrue(safe.findings.stream().allMatch(f->f.severity==Severity.LOW));
        Report argument=analyze("ArgumentCalculator","ArgumentA","ArgumentB");
        Finding f=argument.findings.stream().filter(x->x.rule.equals("MS006")).findFirst().orElseThrow();assertEquals(Severity.MEDIUM,f.severity);assertEquals(Confidence.HIGH,f.confidence);
    }
    @Test void exportStaticReportsForRuntimeEvidenceComparison() throws Exception {
        Map<String,List<String>> cases=new TreeMap<>();
        for(String kind:List.of("Safe","Chain","Method","Bypass","Overwrite","Redirect","Argument"))
            cases.put(kind+"Calculator",List.of(kind+"A",kind+"B"));
        cases.put("DelegatedCalculator",List.of("Delegated"));
        cases.put("InitializerCalculator",List.of("InitializerMixin"));
        cases.put("OptionalInheritedChild",List.of("OptionalInherited"));
        cases.put("RequiredInheritedChild",List.of("RequiredInherited"));
        cases.put("OverwriteInheritedChild",List.of("InheritedOverwrite"));
        cases.put("OwnedOverrideChild",List.of("OwnedOverride"));
        cases.put("ExplicitOwnerCalculator",List.of("ExplicitOwner"));
        Path output=Path.of("build/runtime-fixture/static");Files.createDirectories(output);
        for(var entry:cases.entrySet()) {
            Report report=analyze(entry.getKey(),entry.getValue().toArray(String[]::new));
            assertFalse(report.coverage.partial,entry.getKey());
            Files.writeString(output.resolve(entry.getKey()+".json"),Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        }
    }

}
