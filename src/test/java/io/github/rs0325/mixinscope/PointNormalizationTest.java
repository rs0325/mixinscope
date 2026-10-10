package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PointNormalizationTest {
    @TempDir Path directory;
    private Path referenceMod(String id,String owner,String raw,String normalized,Method method) throws Exception {
        return jar(directory.resolve(id+".jar"),Map.of("fabric.mod.json",Util.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"id",id,"version","1","mixins",List.of("test.mixins.json"))),
                "test.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("TestMixin"),"refmap","test.refmap.json")),
                "test.refmap.json",Util.JSON.writeValueAsBytes(Map.of("mappings",Map.of("example/TestMixin",Map.of(raw,normalized)))),"example/TestMixin.class",mixin(owner,method)));
    }
    @Test void differentRawRefmapAliasesCanSelectOneJarOnlyCandidate() throws Exception {
        String a="La/Ops;call()V",b="Lb/Ops;other()V",normalized="Lgame/Ops;hit()V";
        Report r=scan(options(referenceMod("a","game/Player",a,normalized,new Method("Redirect","attack()V",Map.of("at",at("INVOKE",a,0)))),
                referenceMod("b","game/Player",b,normalized,new Method("Redirect","attack()V",Map.of("at",at("INVOKE",b,0))))));
        Finding f=r.findings.stream().filter(x->x.rule.equals("MS003")).findFirst().orElseThrow();assertEquals(Confidence.MEDIUM,f.confidence);
        assertEquals(2,r.modifications.stream().map(m->m.at.getFirst().get("target")).distinct().count());
        assertTrue(r.modifications.stream().allMatch(m->m.pointCandidatesNormalized&&m.normalizedAt.getFirst().get("target").equals(normalized)));
        assertTrue(r.modifications.stream().allMatch(m->!m.targets.getFirst().instructionsVerified));
    }
    @Test void classStringRefmapIsIndependentOfInjectorRemapFlag() throws Exception {
        Report r=scan(options(referenceMod("a","string:game.Old","game.Old","game/Player",new Method("Inject","attack()V",Map.of("remap",false)))));
        Target t=r.modifications.getFirst().targets.getFirst();assertEquals("game/Old",t.rawOwner);assertEquals("game/Player",t.key.owner());
    }
    @Test void sliceAliasesAreNormalizedBeforeCandidateComparison() throws Exception {
        List<Path> inputs=new ArrayList<>();
        for(String id:List.of("a","b")){
            String raw="L"+id+"/Ops;call()V";
            Map<String,Object> slice=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Slice;","id","part","from",at("INVOKE",raw,0),"to",at("TAIL"));
            var point=new TreeMap<String,Object>(at("INVOKE",raw,1));point.put("slice","part");
            inputs.add(referenceMod(id,"game/Player",raw,"Lgame/Ops;hit()V",new Method("Redirect","attack()V",Map.of("at",point,"slice",slice))));
        }
        Report r=scan(options(inputs.toArray(Path[]::new)));assertTrue(rules(r).contains("MS003"));
        assertEquals(1,r.modifications.stream().map(m->m.normalizedSlices).distinct().count());
    }
    @Test void tinyNormalizesCallCandidatesAndReferenceInstructionsOnce() throws Exception {
        String raw="Lnet/minecraft/class_3;method_3()V";
        var o=options(mod(directory,"a","net/minecraft/class_1","*",new Method("Redirect","method_1()V",Map.of("at",at("INVOKE",raw,0)))));
        Path mapping=directory.resolve("mapping.tiny");Files.writeString(mapping,"tiny\t2\t0\tintermediary\tnamed\nc\tnet/minecraft/class_1\tgame/Player\n\tm\t()V\tmethod_1\tattack\nc\tnet/minecraft/class_3\tgame/Ops\n\tm\t()V\tmethod_3\thit\n");
        o.mappings=mapping;o.namespace="intermediary";o.outputNamespace="named";o.mappingGameVersion="1.21.1";o.gameJar=game(directory);
        Report r=scan(o);var m=r.modifications.getFirst();assertEquals("Lgame/Ops;hit()V",m.normalizedAt.getFirst().get("target"));assertEquals(raw,m.at.getFirst().get("target"));assertEquals(List.of(0),m.targets.getFirst().instructions);
    }
    @Test void literalPointInDifferentNamespaceDoesNotClaimReferenceIdentity() throws Exception {
        var point=new TreeMap<String,Object>(at("INVOKE","Lraw/Ops;hit()V",0));point.put("remap",false);
        var o=options(mod(directory,"a",new Method("Redirect","attack()V",Map.of("at",point))));
        Path mapping=directory.resolve("mapping.tiny");Files.writeString(mapping,"tiny\t2\t0\tintermediary\tnamed\n");o.mappings=mapping;o.namespace="intermediary";o.outputNamespace="named";o.gameJar=game(directory);
        Report r=scan(o);assertFalse(r.modifications.getFirst().pointCandidatesNormalized);assertFalse(r.modifications.getFirst().targets.getFirst().instructionsVerified);assertTrue(diagnostic(r,"POINT_CANDIDATE_NORMALIZATION_UNVERIFIED"));
    }
}
