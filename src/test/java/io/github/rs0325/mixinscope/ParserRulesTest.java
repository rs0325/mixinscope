package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserRulesTest {
    @TempDir Path directory;
    @Test void multipleTargetClassesAreExpanded() throws Exception {
        Report r=scan(options(mod(directory,"a","game/Player,game/Other","*",new Method("Inject","attack()V"))));
        assertEquals(2,r.modifications.getFirst().targets.size());assertEquals(2,r.targetIndex.size());
    }
    @Test void stringTargetOwnerIsNormalized() throws Exception {
        Report r=scan(options(mod(directory,"a","string:game.Player","*",new Method("Inject","attack()V"))));
        assertEquals("game/Player",r.modifications.getFirst().targets.getFirst().key.owner());
    }
    @Test void structuredDescSelectorResolves() throws Exception {
        Map<String,Object> desc=Map.of("annotationType","Lorg/spongepowered/asm/mixin/injection/Desc;","owner",Type.getObjectType("game/Player"),"value","attack","args",List.of(Type.INT_TYPE),"ret",Type.VOID_TYPE);
        Report r=scan(options(mod(directory,"a",new Method("Inject","",Map.of("target",List.of(desc))))));
        Target t=r.modifications.getFirst().targets.getFirst();assertEquals(Resolution.RESOLVED,t.resolution);assertEquals("(I)V",t.key.descriptor());
    }
    @Test void configRequireFallbackPriorityAndOrderAreSeparate() throws Exception {
        Path a=customMod(directory,"a",Map.of("schemaVersion",1,"id","a","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin"),"priority",900,"mixinPriority",1100,"injectors",Map.of("defaultRequire",2)),"game/Player",new Method("Redirect","attack()V",Map.of("require",-1,"order",12000)));
        var defaults=scan(options(a)).modifications.getFirst().defaults;
        assertEquals(900,defaults.get("configPriority"));assertEquals(1100,defaults.get("mixinPriority"));assertEquals(12000,defaults.get("injectorOrder"));assertEquals(2,defaults.get("require"));
    }
    @Test void sameVariableOrArgumentCandidateIsMedium() throws Exception {
        Map<String,Object> o=Map.of("at",at("INVOKE","Lgame/Ops;hit(I)V",0),"index",0);
        Report r=scan(options(mod(directory,"a",new Method("ModifyArg","attack()V",o)),mod(directory,"b",new Method("ModifyArg","attack()V",o))));
        assertTrue(rules(r).contains("MS006"));assertFalse(r.findings.stream().anyMatch(f->f.severity==Severity.HIGH));
    }
    @Test void differentArgumentsDoNotBecomeSameOperandClaim() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("ModifyArg","attack()V",Map.of("index",0))),mod(directory,"b",new Method("ModifyArg","attack()V",Map.of("index",1)))));
        assertFalse(rules(r).contains("MS006"));
    }
    @Test void allArgumentsAndSingleArgumentMayInteract() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("ModifyArgs","attack()V")),mod(directory,"b",new Method("ModifyArg","attack()V",Map.of("index",0)))));
        assertTrue(rules(r).contains("MS006"));
    }
    @Test void wrapperAndRedirectHaveASeparateCandidateRule() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("WrapOperation","attack()V")),mod(directory,"b",new Method("Redirect","attack()V"))));
        assertTrue(rules(r).contains("MS008"));assertFalse(rules(r).contains("MS003"));
    }
    @Test void allSupportedExtrasAreRetained() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("WrapOperation","attack()V"),new Method("WrapMethod","attack()V"),new Method("ModifyExpressionValue","attack()V"),new Method("ModifyReturnValue","attack()V"),new Method("WrapWithCondition","attack()V"))));
        assertEquals(5,r.modifications.size());assertFalse(diagnostic(r,"ANNOTATION_UNSUPPORTED"));
    }
    @Test void refmapContextAndRemapFalseAreDistinct() throws Exception {
        var entries=new TreeMap<String,byte[]>();
        entries.put("fabric.mod.json",Util.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"id","a","mixins",List.of("test.mixins.json"))));
        entries.put("test.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("TestMixin"),"refmap","refs.json")));
        entries.put("refs.json",Util.JSON.writeValueAsBytes(Map.of("mappings",Map.of("example.TestMixin",Map.of("attack()V","defaultName()V")),"data",Map.of("ctx",Map.of("example.TestMixin",Map.of("attack()V","contextName()V"))))));
        entries.put("example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V"),new Method("Inject","attack()V",Map.of("remap",false))));
        ScanOptions o=options(jar(directory.resolve("a.jar"),entries));o.refmapContext="ctx";Report r=scan(o);
        assertEquals(Set.of("contextName","attack"),new TreeSet<>(r.modifications.stream().map(m->m.targets.getFirst().key.name()).toList()));
    }
    @Test void unknownSchemaLowersApplicability() throws Exception {
        Path a=customMod(directory,"a",Map.of("schemaVersion",99,"id","a","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin")),"game/Player",new Method("Inject","attack()V"));
        assertEquals(Applicability.UNKNOWN,scan(options(a)).modifications.getFirst().applicability);
    }
}
