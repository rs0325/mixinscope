package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeBytecodeTest {
    @TempDir Path directory;
    @Test void ownDeclarationResolvesNameOnlyEvenWithIncompleteHierarchy() throws Exception {
        var o=options(mod(directory,"a",new Method("Inject","getAttributeValue")));o.gameJar=jar(directory.resolve("game.jar"),Map.of("game/Player.class",gameClass("game/Player","missing/Parent")));
        Report r=scan(o);assertEquals(Resolution.RESOLVED,r.modifications.getFirst().targets.getFirst().resolution);assertEquals(Existence.VERIFIED,r.modifications.getFirst().targets.getFirst().existence);
    }
    @Test void nameOnlyIgnoresInheritedOverloadsAndExactOverrideWins() throws Exception {
        var o=options(mod(directory,"a","game/Child","*",new Method("Inject","attack"),new Method("Inject","attack()V")));
        ClassWriter child=new ClassWriter(0);child.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"game/Child",null,"game/Player",null);
        var method=child.visitMethod(Opcodes.ACC_PUBLIC,"attack","()V",null,null);method.visitCode();method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,1);method.visitEnd();child.visitEnd();
        o.gameJar=jar(directory.resolve("reference.jar"),Map.of("game/Child.class",child.toByteArray(),"game/Player.class",gameClass("game/Player","java/lang/Object")));
        Report r=scan(o);Target named=r.modifications.stream().filter(m->m.selectors.contains("attack")).findFirst().orElseThrow().targets.getFirst();
        assertEquals(Resolution.RESOLVED,named.resolution);assertEquals(List.of("game/Child.attack()V"),named.candidates);
        Target exact=r.modifications.stream().filter(m->m.selectors.contains("attack()V")).findFirst().orElseThrow().targets.getFirst();assertEquals("game/Child",exact.declaringOwner);
    }
    @Test void malformedPointIsUnsupportedEvenWhenNoCorrespondingInstructionExists() throws Exception {
        for(String kind:List.of("FIELD","NEW","INVOKE")) {
            var o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",at(kind,"",-1)))));o.gameJar=game(directory);Report r=scan(o);
            assertTrue(diagnostic(r,"INJECTION_POINT_UNSUPPORTED"),kind);assertFalse(r.modifications.getFirst().targets.getFirst().instructionsVerified,kind);
        }
    }
    @Test void shiftAcrossLabelDoesNotClaimOpcodeOnlyPosition() throws Exception {
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"game/Player",null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"attack","()V",null,null);method.visitCode();Label end=new Label();
        method.visitJumpInsn(Opcodes.GOTO,end);method.visitLabel(end);method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,1);method.visitEnd();writer.visitEnd();
        var point=new TreeMap<String,Object>(at("HEAD"));point.put("shift",new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;","AFTER"});
        var o=options(mod(directory,"a",new Method("Inject","attack()V",Map.of("at",point))));o.gameJar=jar(directory.resolve("game.jar"),Map.of("game/Player.class",writer.toByteArray()));
        Report r=scan(o);assertTrue(diagnostic(r,"INJECTION_POINT_UNSUPPORTED"));assertFalse(r.modifications.getFirst().targets.getFirst().instructionsVerified);
    }
    @Test void implicitArgumentOverlapKeepsMediumConfidence() throws Exception {
        var o=options(mod(directory,"a",new Method("ModifyArg","attack()V",Map.of("at",at("INVOKE","Lgame/Ops;hit()V",-1)))),
                mod(directory,"b",new Method("ModifyArg","attack()V",Map.of("at",at("INVOKE","Lgame/Ops;hit()V",-1),"index",0))));o.gameJar=game(directory);
        Finding f=scan(o).findings.stream().filter(x->x.rule.equals("MS006")).findFirst().orElseThrow();assertEquals(Confidence.MEDIUM,f.confidence);
    }
}
