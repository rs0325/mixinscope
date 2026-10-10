package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.resolver.*;
import io.github.rs0325.mixinscope.report.*;
import io.github.rs0325.mixinscope.cli.Main;
import io.github.rs0325.mixinscope.archive.ArchiveReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @TempDir Path directory;
    @Test void unsupportedPointsNeverBecomeStrongRedirectCandidatesEvenWithoutReference()throws Exception {
        List<Map<String,Object>> points=List.of(at("custom:point","opaque",0),at("INVOKE","",0),at("FIELD","Lgame/Owner;value:V",0),
                at("NEW","[Lgame/Owner;",0),new TreeMap<>(Map.of("value","INVOKE","target","Lgame/Ops;hit()V","args",List.of("unknown=true"))),
                at("RETURN","",-2),new TreeMap<>(Map.of("value","HEAD","shift","UNKNOWN")),new TreeMap<>(Map.of("value","CONSTANT","constant",List.of(Map.of("intValue",0,"ordinal",0)))));
        int count=0;
        for(var point:points)for(boolean reference:List.of(false,true)) {
            Path location=directory.resolve("case"+count++);var options=options(mod(location,"a",new Method("Redirect","attack()V",Map.of("at",point))),mod(location,"b",new Method("Redirect","attack()V",Map.of("at",point))));
            if(reference)options.gameJar=game(location);Report report=scan(options);
            assertEquals(Set.of("MS001"),rules(report),point.toString());assertTrue(diagnostic(report,"INJECTION_POINT_UNSUPPORTED"));
            assertTrue(report.modifications.stream().allMatch(m->!m.pointCandidatesNormalized&&!m.targets.getFirst().instructionsVerified));
        }
    }
    @Test void duplicateSliceIdsDoNotChooseTheFirstBoundaryOrClaimStrongOverlap()throws Exception {
        Map<String,Object> slice=Map.of("id","part","from",at("HEAD"),"to",at("TAIL"));var point=new TreeMap<>(at("INVOKE","Lgame/Ops;hit()V",0));point.put("slice","part");
        var options=options(mod(directory,"a",new Method("Redirect","attack()V",Map.of("at",point,"slice",List.of(slice,slice)))),mod(directory,"b",new Method("Redirect","attack()V",Map.of("at",point,"slice",List.of(slice,slice)))));options.gameJar=game(directory);
        Report report=scan(options);assertEquals(Set.of("MS001"),rules(report));assertTrue(diagnostic(report,"INJECTION_POINT_UNSUPPORTED"));
    }
    @Test void instructionWalkHonorsBudgetDuringOneLargeMethod() {
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,"large","()V",null,null);for(int i=0;i<8000;i++)method.instructions.add(new InsnNode(Opcodes.NOP));method.instructions.add(new InsnNode(Opcodes.RETURN));
        Modification modification=new Modification();modification.id="fixture";modification.kind="Inject";Target target=new Target();AtomicInteger checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->InstructionMatcher.resolve(method,modification,target,null,s->s,new Report(),()->{if(checks.incrementAndGet()==4)throw new IllegalStateException("budget");}));
        assertFalse(target.instructionsVerified);assertEquals(4,checks.get());
    }
    @Test void mappingParseHonorsBudgetBetweenRecords()throws Exception {
        Path path=directory.resolve("large.tiny");StringBuilder data=new StringBuilder("tiny\t2\t0\ta\tb\n");for(int i=0;i<1000;i++)data.append("c\ta/").append(i).append("\tb/").append(i).append('\n');Files.writeString(path,data);
        AtomicInteger checks=new AtomicInteger();assertThrows(IllegalStateException.class,()->new TinyMappings(path,"a","b",100_000,()->{if(checks.incrementAndGet()==8)throw new IllegalStateException("budget");}));assertEquals(8,checks.get());
    }
    private Report conflict()throws Exception{return scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V"))));}
    @Test void typoCannotBroadenSuppressionAndInvalidFileCannotApplyPartially()throws Exception {
        Report report=conflict();Path file=directory.resolve("rules.json");Files.writeString(file,"{\"suppressions\":[{\"rule\":\"MS002\",\"reason\":\"known\"},{\"rule\":\"MS001\",\"targett\":\"narrow\",\"reason\":\"typo\"}]}");
        assertThrows(IOException.class,()->ReportHistory.suppress(report,file,LocalDate.of(2026,10,10)));assertTrue(report.findings.stream().noneMatch(f->f.suppressed));assertEquals(0,report.coverage.suppressedFindings);
        for(String invalid:List.of("{}","{\"suppressions\":{}}","{\"suppressions\":[{\"mod\":null,\"reason\":\"test\"}]}")) {Files.writeString(file,invalid);assertThrows(IOException.class,()->ReportHistory.suppress(report,file,LocalDate.now()));}
    }
    @Test void malformedBaselineCannotAcquireDefaultCurrentVersionOrHideDuplicateIds()throws Exception {
        Report report=conflict();var original=Util.JSON.readTree(Reports.json(report));Path file=directory.resolve("before.json");
        for(String field:List.of("schemaVersion","rulesetVersion","coverage","findings","environment")) {var node=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)node).remove(field);Files.writeString(file,node.toString());assertThrows(IOException.class,()->ReportHistory.baseline(report,file),field);assertTrue(report.baseline.isEmpty());}
        var duplicate=original.deepCopy();var findings=(com.fasterxml.jackson.databind.node.ArrayNode)duplicate.get("findings");findings.add(findings.get(0).deepCopy());Files.writeString(file,duplicate.toString());assertThrows(IOException.class,()->ReportHistory.baseline(report,file));
    }
    @Test void historyErrorIsExitTwoWithAnInspectibleJsonReport()throws Exception {
        Path mod=mod(directory,"a",new Method("Overwrite","attack()V"));Path history=directory.resolve("bad.json");Files.writeString(history,"{}");
        for(String option:List.of("--baseline","--suppressions")) {StringWriter out=new StringWriter();var command=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(new StringWriter()));assertEquals(2,command.execute("scan",mod.toString(),option,history.toString(),"--format","json"));assertTrue(Util.JSON.readTree(out.toString()).path("coverage").path("partial").asBoolean());assertTrue(out.toString().contains("HISTORY_ERROR"));}
    }
    @Test void brokenStdoutIsOutputErrorAndExitTwo()throws Exception {
        Writer broken=new Writer(){public void write(char[] c,int o,int l)throws IOException{throw new IOException("closed output");}public void flush(){}public void close(){}};
        StringWriter errors=new StringWriter();var command=Main.commandLine().setOut(new PrintWriter(broken)).setErr(new PrintWriter(errors));Path input=mod(directory,"a",new Method("Inject","attack()V"));
        assertEquals(2,command.execute("scan",input.toString(),"--format","json"));assertTrue(errors.toString().contains("OUTPUT_ERROR"));
    }
    @Test void directorySelectionIsBoundedAndDeterministic()throws Exception {
        for(String name:List.of("z","b","a"))jar(directory.resolve(name+".jar"),Map.of("marker",name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ScanOptions options=new ScanOptions();options.maxEntries=2;options.startBudget();Report report=new Report();
        var roots=new ArchiveReader(options,report).roots(List.of(directory));
        assertEquals(List.of("a.jar","b.jar"),roots.stream().flatMap(a->a.origins.stream()).sorted().toList());
        assertTrue(report.coverage.partial);assertTrue(diagnostic(report,"DIRECTORY_INPUT_LIMIT"));
    }
    @Test void expiredArchiveBudgetAbortsBeforeGeneratingErrorsForEveryRemainingInput()throws Exception {
        ScanOptions options=new ScanOptions();options.timeoutSeconds=0;options.startBudget();Report report=new Report();
        assertThrows(IllegalStateException.class,()->new ArchiveReader(options,report).roots(List.of(directory.resolve("one.jar"),directory.resolve("two.jar"))));
        assertTrue(report.coverage.diagnostics.isEmpty());
    }
}
