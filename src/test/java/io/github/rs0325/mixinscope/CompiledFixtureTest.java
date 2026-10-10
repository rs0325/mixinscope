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
        var o=options(jars.toArray(Path[]::new));o.gameJar=jar(directory.resolve("reference.jar"),Map.of(ROOT+"target/"+target+".class",compiled("target/"+target)));
        return scan(o);
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
    @Test void validNonCancellableCallbacksStayLowAndExplicitArgumentIdentityIsVerified() throws Exception {
        Report safe=analyze("SafeCalculator","SafeA","SafeB");assertEquals(Set.of("MS001"),rules(safe));assertTrue(safe.findings.stream().allMatch(f->f.severity==Severity.LOW));
        Report argument=analyze("ArgumentCalculator","ArgumentA","ArgumentB");
        Finding f=argument.findings.stream().filter(x->x.rule.equals("MS006")).findFirst().orElseThrow();assertEquals(Severity.MEDIUM,f.severity);assertEquals(Confidence.HIGH,f.confidence);
    }
}
