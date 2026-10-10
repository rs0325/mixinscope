package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.resolver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ResolutionTest {
    @TempDir Path directory;
    private static byte[] json(Object value) throws java.io.IOException { return Util.JSON.writeValueAsBytes(value); }
    private Path redirect(String id,int ordinal) throws Exception { return mod(directory,id,new Method("Redirect","attack()V",Map.of("at",at("INVOKE","Lgame/Ops;hit()V",ordinal)))); }
    @Test void jarOnlyIdenticalRedirectCandidatesHaveMediumConfidence() throws Exception {
        Report report=scan(options(redirect("a",0),redirect("b",0)));
        Finding f=report.findings.stream().filter(v->v.rule.equals("MS003")).findFirst().orElseThrow(); assertEquals(Confidence.MEDIUM,f.confidence);
    }
    @Test void sameInstructionRedirectsAreVerified() throws Exception {
        ScanOptions o=options(redirect("a",0),redirect("b",0)); o.gameJar=game(directory);
        Report report=scan(o); Finding f=report.findings.stream().filter(v->v.rule.equals("MS003")).findFirst().orElseThrow(); assertEquals(Confidence.HIGH,f.confidence);
        for(Modification m:report.modifications) {Target t=m.targets.getFirst(); assertEquals(Existence.VERIFIED,t.existence); assertEquals(List.of(0),t.instructions); assertTrue(t.instructionsVerified);}
    }
    @Test void sameCallAtDifferentInstructionsDoesNotBecomeRedirectCollision() throws Exception {
        ScanOptions o=options(redirect("a",0),redirect("b",1)); o.gameJar=game(directory); Report report=scan(o);
        assertFalse(rules(report).contains("MS003")); assertEquals(Set.of("MS001"),rules(report));
    }
    @Test void sameSliceIdWithDifferentBoundariesDoesNotEstablishJarOnlyRedirectCollision() throws Exception {
        Map<String,Object> point=new TreeMap<>(at("INVOKE","Lgame/Ops;hit()V",0));point.put("slice","range");
        Map<String,Object> first=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Slice;","id","range","from",at("HEAD"));
        Map<String,Object> second=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Slice;","id","range","from",at("INVOKE","Lgame/Ops;hit()V",1));
        Report report=scan(options(mod(directory,"a",new Method("Redirect","attack()V",Map.of("at",point,"slice",first))),
                mod(directory,"b",new Method("Redirect","attack()V",Map.of("at",point,"slice",second)))));
        assertEquals(Set.of("MS001"),rules(report));
    }
    @Test void ordinalAllIntersectsAnIndividualInstruction() throws Exception {
        ScanOptions o=options(redirect("a",-1),redirect("b",1)); o.gameJar=game(directory); assertTrue(rules(scan(o)).contains("MS003"));
    }
    @Test void missingInjectionPointCanBeReportedWithoutClaimingFailure() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",at("INVOKE","Lgame/Ops;absent()V",0),"require",1)))); o.gameJar=game(directory);
        Report report=scan(o); assertTrue(diagnostic(report,"INJECTION_COUNT_CANDIDATE")); assertFalse(report.coverage.partial); assertTrue(report.modifications.getFirst().targets.getFirst().instructions.isEmpty());
    }
    @Test void unsupportedAtDoesNotBecomeVerified() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",at("CUSTOM:POINT"))))); o.gameJar=game(directory);
        Report report=scan(o); assertTrue(diagnostic(report,"INJECTION_POINT_UNSUPPORTED")); assertFalse(report.modifications.getFirst().targets.getFirst().instructionsVerified);
    }
    @Test void returnAndTailSelectReturnInstruction() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",at("RETURN")))),mod(directory,"b",new Method("Inject","attack()V",Map.of("at",at("TAIL"))))); o.gameJar=game(directory);
        Report report=scan(o); assertTrue(report.modifications.stream().allMatch(m->m.targets.getFirst().instructions.equals(List.of(2))));
    }
    @Test void constantSelectorMatchesTypedConstant() throws Exception {
        Map<String,Object> constant=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Constant;","doubleValue",0d);
        ScanOptions o=options(mod(directory,"a",new Method("ModifyConstant","getAttributeValue()D",Map.of("constant",constant,"at",List.of())))); o.gameJar=game(directory);
        Report report=scan(o); assertEquals(List.of(0),report.modifications.getFirst().targets.getFirst().instructions);
    }
    @Test void absentMethodInCompleteHierarchyIsMissing() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","absent()V"))); o.gameJar=game(directory);
        Report report=scan(o); assertTrue(diagnostic(report,"TARGET_MISSING")); assertEquals(Existence.MISSING,report.modifications.getFirst().targets.getFirst().existence);
    }
    @Test void inheritedDeclaringOwnerIsInformationalOnly() throws Exception {
        Path jar=jar(directory.resolve("reference.jar"),Map.of("game/Child.class",emptyClass("game/Child","game/Player"),"game/Player.class",gameClass("game/Player","java/lang/Object")));
        ScanOptions o=options(mod(directory,"a","game/Child","*",new Method("Inject","attack()V"))); o.gameJar=jar;
        Report r=scan(o);Target t=r.modifications.getFirst().targets.getFirst();
        assertEquals("game/Child",t.key.owner());assertEquals("game/Player",t.declaringOwner);assertEquals(Resolution.UNRESOLVED,t.resolution);assertFalse(t.instructionsVerified);assertTrue(t.candidateKeys.isEmpty());assertTrue(diagnostic(r,"TARGET_INHERITED_NOT_LOCAL"));
    }
    @Test void incompleteSuperclassDoesNotProveMethodAbsence() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","absent()V"))); o.gameJar=jar(directory.resolve("ref.jar"),Map.of("game/Player.class",gameClass("game/Player","game/AbsentParent")));
        Report report=scan(o); assertEquals(Existence.UNVERIFIED,report.modifications.getFirst().targets.getFirst().existence); assertTrue(diagnostic(report,"REFERENCE_HIERARCHY_INCOMPLETE"));
    }
    private byte[] emptyClass(String owner,String parent) {var w=new org.objectweb.asm.ClassWriter(0);w.visit(org.objectweb.asm.Opcodes.V21,1,owner,null,parent,null);w.visitEnd();return w.toByteArray();}
    private Path mappings() throws Exception {
        Path file=directory.resolve("mappings.tiny"); Files.writeString(file,"tiny\t2\t0\tintermediary\tnamed\nc\tnet/minecraft/class_1\tgame/Player\n\tm\t()V\tmethod_1\tattack\n\tm\t(Lnet/minecraft/class_2;)Lnet/minecraft/class_2;\tmethod_2\tresolve\nc\tnet/minecraft/class_2\tgame/Entity\n"); return file;
    }
    @Test void tinyNormalizesOwnerNameAndDescriptors() throws Exception {
        TinyMappings mapping=new TinyMappings(mappings(),"intermediary","named",1024*1024);
        assertEquals("game/Player",mapping.owner("net/minecraft/class_1")); assertEquals("attack",mapping.method("net/minecraft/class_1","method_1","()V"));
        assertEquals("(Lgame/Entity;)Lgame/Entity;",mapping.descriptor("(Lnet/minecraft/class_2;)Lnet/minecraft/class_2;"));
        TinyMappings reverse=new TinyMappings(mappings(),"named","intermediary",1024*1024);
        assertEquals("method_2",reverse.method("game/Player","resolve","(Lgame/Entity;)Lgame/Entity;"));
    }
    @Test void mappingAssistedTargetResolvesAgainstGame() throws Exception {
        ScanOptions o=options(mod(directory,"a","net/minecraft/class_1","*",new Method("Inject","method_1()V")));
        o.namespace="intermediary";o.outputNamespace="named";o.mappings=mappings();o.mappingGameVersion="1.21.1";o.gameJar=game(directory);
        Target t=scan(o).modifications.getFirst().targets.getFirst(); assertEquals("game/Player",t.key.owner()); assertEquals("attack",t.key.name()); assertEquals(Existence.VERIFIED,t.existence);
    }
    @Test void versionMismatchedMappingIsRejected() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V")));o.namespace="intermediary";o.outputNamespace="named";o.mappings=mappings();o.mappingGameVersion="1.20.1";
        Report report=scan(o);assertTrue(diagnostic(report,"MAPPING_ERROR"));assertTrue(report.coverage.partial);
    }
    @Test void contradictoryMappingIsRejected() throws Exception {
        Path file=directory.resolve("bad.tiny");Files.writeString(file,"tiny\t2\t0\ta\tb\nc\tX\tY\nc\tX\tZ\n");
        assertThrows(java.io.IOException.class,()->new TinyMappings(file,"a","b",1024));
    }
    @Test void gameNamespaceMismatchDoesNotValidateTargets() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V")));o.gameJar=game(directory);o.gameNamespace="other";
        Report report=scan(o);assertTrue(diagnostic(report,"REFERENCE_NAMESPACE_MISMATCH"));assertEquals(Existence.UNVERIFIED,report.modifications.getFirst().targets.getFirst().existence);
    }
    @Test void remapFalsePreservesLiteralName() throws Exception {
        ScanOptions o=options(mod(directory,"a","net/minecraft/class_1","*",new Method("Inject","method_1()V",Map.of("remap",false))));o.namespace="intermediary";o.outputNamespace="named";o.mappings=mappings();
        Target target=scan(o).modifications.getFirst().targets.getFirst();assertEquals("method_1",target.key.name());assertEquals("intermediary",target.key.namespace());
    }
    @Test void refmapContextRemapsInjectionPointsAndSliceBoundariesWithoutChangingRawMetadata() throws Exception {
        String raw="Lraw/Ops;rawHit()V", mapped="Lgame/Ops;hit()V";
        Map<String,Object> slice=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Slice;","id","second","from",at("INVOKE",raw,1),"to",at("TAIL"));
        Map<String,Object> point=new TreeMap<>(at("INVOKE",raw,0));point.put("slice","second");
        Path file=jar(directory.resolve("refmap.jar"),Map.of("fabric.mod.json",json(Map.of("schemaVersion",1,"id","refmap","mixins",List.of("test.mixins.json"))),
                "test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"),"refmap","test.refmap.json")),
                "test.refmap.json",json(Map.of("mappings",Map.of("example/TestMixin",Map.of(raw,"Lwrong/Ops;wrong()V")),"data",Map.of("test-context",Map.of("example/TestMixin",Map.of(raw,mapped))))),
                "example/TestMixin.class",mixin("game/Player",new Method("Redirect","attack()V",Map.of("at",point,"slice",slice)))));
        ScanOptions o=options(file);o.gameJar=game(directory);o.refmapContext="test-context";
        Report report=scan(o);Modification m=report.modifications.getFirst();assertEquals(raw,m.at.getFirst().get("target"));
        assertEquals(List.of(1),m.targets.getFirst().instructions);assertTrue(m.targets.getFirst().instructionsVerified);
        o.refmapContext=null;Report fallback=scan(o);assertTrue(diagnostic(fallback,"INJECTION_POINT_UNSUPPORTED"));
    }
    @Test void atRemapFalseBypassesRefmapAndEmptyPointsRemainVisibleWithRequireZero() throws Exception {
        String raw="Lraw/Ops;rawHit()V";Map<String,Object> point=new TreeMap<>(at("INVOKE",raw,0));point.put("remap",false);
        Path file=jar(directory.resolve("literal.jar"),Map.of("fabric.mod.json",json(Map.of("schemaVersion",1,"id","literal","mixins",List.of("test.mixins.json"))),
                "test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"),"refmap","test.refmap.json")),
                "test.refmap.json",json(Map.of("mappings",Map.of("example/TestMixin",Map.of(raw,"Lgame/Ops;hit()V")))),
                "example/TestMixin.class",mixin("game/Player",new Method("Redirect","attack()V",Map.of("at",point,"require",0)))));
        ScanOptions o=options(file);o.gameJar=game(directory);Report report=scan(o);
        assertTrue(report.modifications.getFirst().targets.getFirst().instructions.isEmpty());assertTrue(diagnostic(report,"INJECTION_POINT_NO_MATCH"));
        assertFalse(diagnostic(report,"INJECTION_COUNT_CANDIDATE"));
    }
    @Test void sliceAndShiftRestrictInstructionMatches() throws Exception {
        Map<String,Object> slice=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Slice;","id","second","from",at("INVOKE","Lgame/Ops;hit()V",1),"to",at("TAIL"));
        Map<String,Object> point=new TreeMap<>(at("INVOKE","Lgame/Ops;hit()V",0));point.put("slice","second");point.put("shift",new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;","AFTER"});
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",point,"slice",slice))));o.gameJar=game(directory);
        assertEquals(List.of(2),scan(o).modifications.getFirst().targets.getFirst().instructions);
    }
}
