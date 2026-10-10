package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.resolver.TargetResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectorSafetyTest {
    @TempDir Path directory;
    @Test void wildcardSemanticsMatchAnIndependentSmallPatternOracle() {
        Random random=new Random(710);
        for(int sample=0;sample<2000;sample++) {
            StringBuilder pattern=new StringBuilder(),value=new StringBuilder();
            for(int i=0,n=random.nextInt(10);i<n;i++)pattern.append("ab*?$".charAt(random.nextInt(5)));
            for(int i=0,n=random.nextInt(10);i<n;i++)value.append("ab?$".charAt(random.nextInt(4)));
            String regex=java.util.regex.Pattern.quote(pattern.toString()).replace("*","\\E.*\\Q");
            assertEquals(value.toString().matches(regex),Util.matchesGlob(pattern.toString(),value.toString(),()->{}),pattern+" / "+value);
        }
        assertTrue(Util.matchesGlob("a*b","a\nb",()->{}));
    }
    @Test void manyStarsDoNotTriggerRegexBacktrackingInTheResolver() throws Exception {
        var o=options(mod(directory,"a",new Method("Inject","a*".repeat(256)+"z()V")),mod(directory,"b",new Method("Overwrite","a".repeat(256)+"y()V")));
        Report r=scan(o);assertFalse(r.coverage.partial);assertTrue(r.findings.isEmpty());
        assertTrue(r.modifications.stream().filter(m->m.kind.equals("Inject")).findFirst().orElseThrow().targets.getFirst().candidateKeys.isEmpty());
    }
    @Test void longWildcardWorkChecksTheScanBudgetDuringMatching() {
        AtomicInteger checks=new AtomicInteger();
        var error=assertThrows(IllegalStateException.class,()->Util.matchesGlob("*"+"a".repeat(1000)+"b","a".repeat(5000),()->{if(checks.incrementAndGet()==4)throw new IllegalStateException("Budget expired");}));
        assertEquals("Budget expired",error.getMessage());assertEquals(4,checks.get());
    }
    @Test void descriptorsAreConsumedWithoutARegexStackAndRespectJvmBounds() {
        assertTrue(TargetResolver.validDescriptor("("+"I".repeat(255)+")V"));
        assertFalse(TargetResolver.validDescriptor("("+"I".repeat(60_000)+")V"));
        assertFalse(TargetResolver.validDescriptor("("+"J".repeat(128)+")V"));
        assertTrue(TargetResolver.validDescriptor("()"+"[".repeat(255)+"I"));
        assertFalse(TargetResolver.validDescriptor("()"+"[".repeat(256)+"I"));
        for(String bad:List.of("()VV","(V)V","(L;)V","()Lunclosed","()Lbad.name;"))assertFalse(TargetResolver.validDescriptor(bad),bad);
        assertTrue(TargetResolver.validFieldDescriptor("[Ljava/lang/String;"));assertFalse(TargetResolver.validFieldDescriptor("V"));
    }
    @Test void ownerSelectorProductIsCappedAndRawDeclarationsRemainAvailable() throws Exception {
        var o=options(mod(directory,"a","game/A,game/B,game/C","*",new Method("Inject","attack()V"),new Method("Inject","other()V")));o.maxTargets=2;
        Report r=scan(o);assertEquals(2,r.modifications.stream().mapToInt(m->m.targets.size()).sum());assertEquals(4,r.coverage.skippedTargets);assertTrue(diagnostic(r,"TARGET_LIMIT"));
        assertTrue(r.modifications.stream().allMatch(m->m.owners.size()==3&&m.selectors.size()==1));assertFalse(r.coverage.partial);
    }
    private byte[] empty(String name,String parent,String... interfaces) {
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,name,null,parent,interfaces);writer.visitEnd();return writer.toByteArray();
    }
    @Test void deepAndCyclicReferenceHierarchiesNeverBecomeProvenMissing() throws Exception {
        Map<String,byte[]> entries=new TreeMap<>();
        for(int i=0;i<300;i++)entries.put("game/C"+i+".class",empty("game/C"+i,i<299?"game/C"+(i+1):"java/lang/Object"));
        var o=options(mod(directory,"a","game/C0","*",new Method("Inject","attack")));o.gameJar=jar(directory.resolve("deep.jar"),entries);Report r=scan(o);
        assertTrue(diagnostic(r,"REFERENCE_HIERARCHY_LIMIT"));assertNotEquals(Existence.MISSING,r.modifications.getFirst().targets.getFirst().existence);assertTrue(r.modifications.getFirst().targets.getFirst().candidatesTruncated);
        o.gameJar=jar(directory.resolve("cycle.jar"),Map.of("game/C0.class",empty("game/C0","game/C1"),"game/C1.class",empty("game/C1","game/C0")));r=scan(o);
        assertTrue(diagnostic(r,"REFERENCE_HIERARCHY_CYCLE"));assertNotEquals(Existence.MISSING,r.modifications.getFirst().targets.getFirst().existence);
    }
    @Test void referenceClassPathMismatchDoesNotVerifyTheInternalOwner() throws Exception {
        var o=options(mod(directory,"a",new Method("Inject","attack()V")));o.gameJar=jar(directory.resolve("wrong-path.jar"),Map.of("game/Other.class",gameClass("game/Player","java/lang/Object")));Report r=scan(o);
        assertTrue(r.coverage.partial);assertTrue(diagnostic(r,"REFERENCE_CLASS_ERROR"));assertEquals(Existence.UNVERIFIED,r.modifications.getFirst().targets.getFirst().existence);
    }
    @Test void missingPlatformObjectIsAnIncompleteHierarchy() throws Exception {
        var o=options(mod(directory,"a",new Method("Inject","getClass()Ljava/lang/Class;"),new Method("Inject","absent()V")));
        o.gameJar=jar(directory.resolve("without-platform.jar"),Map.of("game/Player.class",gameClass("game/Player","java/lang/Object")));Report r=scan(o);
        assertTrue(diagnostic(r,"REFERENCE_HIERARCHY_INCOMPLETE"));assertTrue(r.modifications.stream().allMatch(m->m.targets.getFirst().existence==Existence.UNVERIFIED));assertFalse(diagnostic(r,"TARGET_MISSING"));
    }
    @Test void primitiveAndArrayMixinTargetsRemainUnknownRatherThanClassIdentities() throws Exception {
        var node=new org.objectweb.asm.tree.ClassNode();new ClassReader(mixin("game/Player",new Method("Inject","attack()V"))).accept(node,0);
        node.invisibleAnnotations.getFirst().values.set(1,List.of(Type.INT_TYPE,Type.getType("[Ljava/lang/String;")));ClassWriter writer=new ClassWriter(0);node.accept(writer);
        Path file=jar(directory.resolve("invalid-targets.jar"),Map.of("fabric.mod.json",Util.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"id","invalid_targets","mixins",List.of("test.mixins.json"))),
                "test.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",writer.toByteArray()));
        Report r=scan(options(file));assertTrue(diagnostic(r,"MIXIN_TARGET_TYPE_UNSUPPORTED"));assertEquals(Applicability.UNKNOWN,r.modifications.getFirst().applicability);assertTrue(r.modifications.getFirst().owners.isEmpty());assertTrue(r.findings.isEmpty());
    }
    @Test void overwriteAliasesAreRetainedAsAnUnresolvedCoverageGap() throws Exception {
        Report r=scan(options(mod(directory,"a",new Method("Overwrite","attack()V",Map.of("aliases",List.of("legacyAttack"))))));
        assertTrue(diagnostic(r,"OVERWRITE_ALIASES_UNVERIFIED"));assertEquals(List.of("legacyAttack"),r.modifications.getFirst().options.get("aliases"));
    }
    @Test void interfaceDeclarationsDoNotBecomeLocalInjectionTargets() throws Exception {
        Map<String,byte[]> entries=new TreeMap<>();entries.put("game/Child.class",empty("game/Child","game/Parent","game/J"));entries.put("game/Parent.class",empty("game/Parent","java/lang/Object","game/I"));
        for(String owner:List.of("game/I","game/J")) {
            ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT,owner,null,"java/lang/Object",null);
            writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"attack","()V",null,null).visitEnd();writer.visitEnd();entries.put(owner+".class",writer.toByteArray());
        }
        var o=options(mod(directory,"a","game/Child","*",new Method("Inject","attack()V")));o.gameJar=jar(directory.resolve("interfaces.jar"),entries);Target t=scan(o).modifications.getFirst().targets.getFirst();
        assertEquals(Resolution.UNRESOLVED,t.resolution);assertTrue(t.candidates.isEmpty());assertNull(t.declaringOwner);assertFalse(t.instructionsVerified);
    }
}
