package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.cli.Main;
import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportCliTest {
    @TempDir Path directory;
    record Output(int exit,String out,String err) {}
    private Output cli(String... args){StringWriter out=new StringWriter(),err=new StringWriter();var cmd=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err));int exit=cmd.execute(args);return new Output(exit,out.toString(),err.toString());}
    private Report conflict() throws Exception {return scan(options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V"))));}
    @Test void canonicalJsonDoesNotDependOnInputOrderOrAbsoluteDirectory() throws Exception {
        Path a=mod(directory.resolve("first"),"a",new Method("Inject","attack()V")),b=mod(directory.resolve("first"),"b",new Method("Inject","attack()V"));
        Path copyA=directory.resolve("second/a.jar"),copyB=directory.resolve("second/b.jar");Files.createDirectories(copyA.getParent());Files.copy(a,copyA);Files.copy(b,copyB);
        assertEquals(Reports.json(scan(options(a,b))),Reports.json(scan(options(copyB,copyA))));
    }
    @Test void reportRoundtripPreservesInventoryAndFindingIds() throws Exception {
        Report before=conflict();Report after=Util.JSON.readValue(Reports.json(before),Report.class);assertEquals(before.findings.getFirst().id,after.findings.getFirst().id);assertEquals(before.targetIndex,after.targetIndex);
    }
    @Test void suppressionRetainsFindingAndRequiresReason() throws Exception {
        Report report=conflict();Path file=directory.resolve("suppress.json");Files.writeString(file,"{\"suppressions\":[{\"rule\":\"MS002\",\"reason\":\"Known combination\"}]}");
        ReportHistory.suppress(report,file,LocalDate.of(2026,10,10));assertTrue(report.findings.stream().filter(f->f.rule.equals("MS002")).allMatch(f->f.suppressed));assertEquals(1,report.coverage.suppressedFindings);
        Files.writeString(file,"{\"suppressions\":[{\"rule\":\"MS002\"}]}");assertThrows(IOException.class,()->ReportHistory.suppress(report,file,LocalDate.of(2026,10,10)));
    }
    @Test void expiredSuppressionDoesNotHideFinding() throws Exception {
        Report report=conflict();Path file=directory.resolve("suppress.json");Files.writeString(file,"{\"suppressions\":[{\"rule\":\"MS002\",\"reason\":\"Old\",\"expires\":\"2026-10-09\"}]}");
        ReportHistory.suppress(report,file,LocalDate.of(2026,10,10));assertEquals(0,report.coverage.suppressedFindings);assertTrue(diagnostic(report,"SUPPRESSION_EXPIRED"));
    }
    @Test void identicalBaselineIsUnchanged() throws Exception {
        Report previous=conflict();Path file=directory.resolve("baseline.json");Files.writeString(file,Reports.json(previous));Report current=conflict();ReportHistory.baseline(current,file);
        assertEquals(previous.findings.size(),Util.list(current.baseline.get("unchanged")).size());assertTrue(Util.list(current.baseline.get("new")).isEmpty());
    }
    @Test void partialAnalysisCannotResolveDisappearedFinding() throws Exception {
        Report previous=conflict();Path file=directory.resolve("baseline.json");Files.writeString(file,Reports.json(previous));Report current=new Report();current.environment=previous.environment;current.coverage.partial=true;
        ReportHistory.baseline(current,file);assertTrue(Util.list(current.baseline.get("resolved")).isEmpty());assertEquals(previous.findings.size(),Util.list(current.baseline.get("unverifiedDisappearance")).size());
    }
    @Test void unknownResolutionCannotResolveDisappearedFinding() throws Exception {
        Report previous=conflict();Path file=directory.resolve("baseline.json");Files.writeString(file,Reports.json(previous));Report current=new Report();current.environment=previous.environment;current.coverage.unresolvedTargets=1;
        ReportHistory.baseline(current,file);assertTrue(Util.list(current.baseline.get("resolved")).isEmpty());
    }
    @Test void changedMappingOrRulesetIsIncomparable() throws Exception {
        Report previous=conflict();Path file=directory.resolve("baseline.json");Files.writeString(file,Reports.json(previous));Report current=conflict();current.environment.put("mapping",Map.of("hash","new"));current.rulesetVersion="2";
        ReportHistory.baseline(current,file);assertEquals(true,current.baseline.get("environmentChanged"));assertEquals(true,current.baseline.get("rulesetChanged"));assertEquals(false,current.baseline.get("comparable"));
    }
    @Test void htmlEscapesScriptTerminatorsAndUsesTextNodes() throws Exception {
        Report report=conflict();report.findings.getFirst().reason="</script><script>globalThis.pwned=1</script>";
        String html=Reports.html(report);assertFalse(html.contains("<script>globalThis.pwned"));assertTrue(html.contains("\\u003c/script\\u003e"));assertFalse(html.contains("innerHTML"));assertTrue(html.contains("connect-src 'none'"));
    }
    @Test void jsonStdoutIsNotMixedWithDiagnostics() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack()V"));Output result=cli("scan",a.toString(),"--format","json");
        assertEquals(0,result.exit);assertNotNull(Util.JSON.readTree(result.out).get("coverage"));assertTrue(result.err.contains("NAMESPACE_UNKNOWN"));
    }
    @Test void explicitRiskThresholdUsesExitOne() throws Exception {
        Path a=mod(directory,"a",new Method("Overwrite","attack()V")),b=mod(directory,"b",new Method("Overwrite","attack()V"));
        Output result=cli("scan",a.toString(),b.toString(),"--fail-on","high");assertEquals(1,result.exit);assertTrue(result.out.contains("Risk: HIGH"));
    }
    @Test void partialErrorTakesPrecedenceOverRiskThreshold() throws Exception {
        Path a=mod(directory,"a",new Method("Overwrite","attack()V")),b=mod(directory,"b",new Method("Overwrite","attack()V"));
        Output result=cli("scan",a.toString(),b.toString(),directory.resolve("missing.jar").toString(),"--fail-on","high","--format","json");assertEquals(2,result.exit);assertTrue(Util.JSON.readTree(result.out).get("coverage").get("partial").asBoolean());
    }
    @Test void strictModeFailsOnUnknownCoverage() throws Exception {
        Output result=cli("scan",mod(directory,"a",new Method("Inject","attack()V")).toString(),"--strict");assertEquals(2,result.exit);
    }
    @Test void outputFileAndFilterSelectionPreserveFullInventory() throws Exception {
        Path a=mod(directory,"a",new Method("Overwrite","attack()V")),b=mod(directory,"b",new Method("Overwrite","attack()V"));Path output=directory.resolve("report.json");
        Output result=cli("scan",a.toString(),b.toString(),"--format","json","--output",output.toString(),"--min-severity","high");assertEquals(0,result.exit);assertEquals("",result.out);
        Report report=Util.JSON.readValue(Files.readString(output),Report.class);assertEquals(2,report.findings.size());assertEquals(1,report.visibleFindingIds.size());
    }
    @Test void outputCannotOverwriteInput() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack()V"));byte[] before=Files.readAllBytes(a);
        assertEquals(2,cli("scan",a.toString(),"--output",a.toString()).exit);assertArrayEquals(before,Files.readAllBytes(a));
    }
    @Test void outputCannotOverwriteJarInsideDirectoryInput() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack()V"));byte[] before=Files.readAllBytes(a);
        assertEquals(2,cli("scan",directory.toString(),"--output",a.toString()).exit);assertArrayEquals(before,Files.readAllBytes(a));
    }
    @Test void historyErrorsStillReturnAReport() throws Exception {
        Output result=cli("scan",mod(directory,"a",new Method("Inject","attack()V")).toString(),"--suppressions",directory.resolve("missing.json").toString(),"--format","json");
        assertEquals(2,result.exit);assertTrue(Util.JSON.readTree(result.out).get("coverage").get("partial").asBoolean());
    }
    @Test void conditionalBaselineCannotResolveDisappearance() throws Exception {
        Report previous=conflict();Path file=directory.resolve("baseline.json");Files.writeString(file,Reports.json(previous));Report current=new Report();current.environment=previous.environment;current.coverage.unknownApplicability=1;
        ReportHistory.baseline(current,file);assertEquals(false,current.baseline.get("comparable"));assertTrue(Util.list(current.baseline.get("resolved")).isEmpty());
    }
    @Test void invalidArgumentsAreExitTwo() {assertEquals(2,cli("scan","missing.jar","--loader","invalid").exit);assertEquals(2,cli("scan","missing.jar","--max-total-mib","-1").exit);}
    @Test void noInputJarsDoesNotClaimCompleteSuccess() throws Exception {assertEquals(2,cli("scan",directory.toString()).exit);}
    @Test void stableFindingIdSurvivesArtifactVersionUpdate() throws Exception {
        Report first=conflict();Path a=customMod(directory,"a",Map.of("schemaVersion",1,"id","a","version","2","mixins",List.of("test.mixins.json")),Map.of("package","example","mixins",List.of("TestMixin")),"game/Player",new Method("Overwrite","attack()V"));
        Report second=scan(options(a,directory.resolve("b.jar")));assertEquals(first.findings.stream().map(f->f.id).sorted().toList(),second.findings.stream().map(f->f.id).sorted().toList());
    }
}
