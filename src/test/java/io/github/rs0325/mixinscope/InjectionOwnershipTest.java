package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InjectionOwnershipTest {
    @TempDir Path directory;
    @Test void childInjectorIsNotGroupedWithParentOverwrite() throws Exception {
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"game/Child",null,"game/Player",null);writer.visitEnd();
        var options=options(mod(directory,"child","game/Child","*",new Method("Inject","attack()V")),mod(directory,"parent",new Method("Overwrite","attack()V")));
        options.gameJar=jar(directory.resolve("reference.jar"),Map.of("game/Child.class",writer.toByteArray(),"game/Player.class",gameClass("game/Player","java/lang/Object")));
        Report report=scan(options);assertTrue(report.findings.isEmpty());assertTrue(report.targetGroups.isEmpty());
        var child=report.modifications.stream().filter(m->m.owners.contains("game/Child")).findFirst().orElseThrow();
        assertEquals("game/Child",child.targets.getFirst().key.owner());assertEquals("game/Player",child.targets.getFirst().declaringOwner);
        assertTrue(report.targetIndex.entrySet().stream().filter(e->e.getKey().contains("game/Player.")).noneMatch(e->e.getValue().contains(child.id)));
    }
    @Test void jarOnlyQualifiedSelectorRejectsOtherOwnerButAcceptsOwnOwner() throws Exception {
        for(String selector:List.of("Lgame/Other;attack()V","game.Other.attack()V")) {
            Report report=scan(options(mod(directory,"bad",new Method("Inject",selector)),mod(directory,"other","game/Other","*",new Method("Overwrite","attack()V"))));
            assertTrue(diagnostic(report,"SELECTOR_UNSUPPORTED"));assertTrue(report.findings.isEmpty());
            assertTrue(report.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst().candidateKeys.isEmpty());
        }
        Report own=scan(options(mod(directory,"own",new Method("Inject","Lgame/Player;attack()V")),mod(directory,"replace",new Method("Overwrite","attack()V"))));
        assertEquals(Set.of("MS001","MS004"),rules(own));
    }
    @Test void rulesetChangeCannotCallFormerParentOverlapResolved() throws Exception {
        Report before=scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V"))));
        before.rulesetVersion="2";Path file=directory.resolve("old.json");Files.writeString(file,Reports.json(before));
        Report after=Util.JSON.readValue(Reports.json(before),Report.class);after.rulesetVersion="3";after.findings.clear();
        ReportHistory.baseline(after,file);assertEquals(true,after.baseline.get("rulesetChanged"));assertEquals(false,after.baseline.get("comparable"));
        assertTrue(Util.list(after.baseline.get("resolved")).isEmpty());assertEquals(before.findings.size(),Util.list(after.baseline.get("unverifiedDisappearance")).size());
    }
}
