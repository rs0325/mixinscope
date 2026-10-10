package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtrasProfilesTest {
    @TempDir Path directory;
    @Test void legacyAndV2ConditionsHaveDifferentApplicationStages() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("WrapWithCondition","attack()V"),new Method("WrapWithConditionV2","attack()V"))));
        assertEquals(2,r.modifications.size());assertEquals(2,r.modifications.stream().map(m->m.annotationDescriptor).distinct().count());
        assertEquals(java.util.Set.of("NORMAL_INJECTION","POST_APPLY_WRAP_WITH_CONDITION"),r.modifications.stream().map(m->m.defaults.get("applicationStage")).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void wrapMethodIsAbsentFromTheDeclared032Profile() throws Exception {
        var o=options(mod(directory,"a",new Method("WrapMethod","attack()V")));o.mixinExtrasVersion="0.3.2";Report r=scan(o);
        assertTrue(diagnostic(r,"EXTRAS_KIND_UNAVAILABLE"));assertEquals(Applicability.UNKNOWN,r.modifications.getFirst().applicability);assertEquals("UNKNOWN",r.modifications.getFirst().defaults.get("applicationStage"));
    }
    @Test void knownProfilesCarryFixedSourcesAndSeparateNumericOrderFromStage() throws Exception {
        for(String version:java.util.List.of("0.3.2","0.4.1","0.5.0")){
            var o=options(mod(directory,"a",new Method("WrapOperation","attack()V")));o.mixinExtrasVersion=version;
            var m=scan(o).modifications.getFirst();assertEquals("DECLARED:MixinExtras-"+version,m.defaults.get("extrasProfile"));assertTrue(m.defaults.get("extrasProfileSource").toString().matches("https://github.com/LlamaLad7/MixinExtras/tree/[0-9a-f]{40}"));
            assertEquals(1000,m.defaults.get("injectorOrder"));assertEquals("POST_APPLY_WRAP_OPERATION",m.defaults.get("applicationStage"));assertEquals(1,m.defaults.get("expect"));assertEquals(-1,m.defaults.get("allow"));
        }
    }
    @Test void unknownExtrasVersionDoesNotBorrowAVerifiedStage() throws Exception {
        var o=options(mod(directory,"a",new Method("WrapOperation","attack()V")));o.mixinExtrasVersion="9.9.9";Report r=scan(o);
        assertTrue(diagnostic(r,"EXTRAS_VERSION_UNVERIFIED"));assertEquals("UNKNOWN",r.modifications.getFirst().defaults.get("applicationStage"));
    }
    @Test void omittedOperationCallDoesNotBecomeAHighRiskOrProofOfBypass() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("WrapOperation","attack()V")),mod(directory,"b",new Method("WrapOperation","attack()V"))));
        assertTrue(diagnostic(r,"WRAPPER_CALL_UNVERIFIED"));assertTrue(r.findings.stream().allMatch(f->f.severity==Severity.LOW));
        assertTrue(r.modifications.stream().allMatch(m->m.handlerAnalysis.get("operationCallEvidence").equals("NO_DIRECT_OPERATION_CALL")));
        assertTrue(r.modifications.getFirst().handlerAnalysis.get("limitation").toString().contains("Delegation"));
    }
}
