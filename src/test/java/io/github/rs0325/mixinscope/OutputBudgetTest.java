package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.cli.Main;
import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.Reports;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputBudgetTest {
    @TempDir Path directory;
    private String randomText(int length,String alphabet) {Random random=new Random(90);StringBuilder text=new StringBuilder(length);for(int i=0;i<length;i++)text.append(alphabet.charAt(random.nextInt(alphabet.length())));return text.toString();}
    @Test void byteLimitChecksBeforeWritingAndCountsUtf8RatherThanCharacters() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();var output=new BoundedOutputStream(bytes,5);output.write("あ".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->output.write("あ".getBytes(StandardCharsets.UTF_8)));assertEquals(3,bytes.size());output.write('x');output.write('y');assertEquals(5,bytes.size());assertThrows(IOException.class,()->output.write('z'));
    }
    @Test void expandedJsonIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        Report report=new Report();report.assumptions.add("x".repeat(4096));assertThrows(IOException.class,()->Reports.json(report,1024));assertThrows(IOException.class,()->Reports.validatePayload(report,1024));
        assertTrue(Reports.json(report,16384).contains("x".repeat(4096)));
    }
    @Test void htmlEscapingUsesTheSameOutputBudgetAndEscapesLineSeparators() throws Exception {
        Report report=new Report();report.assumptions.add("<".repeat(2000));String json=Reports.json(report,6000);assertTrue(json.contains("<".repeat(2000)));assertThrows(IOException.class,()->Reports.html(report,6000));
        report.assumptions=List.of("<>&\u2028\u2029");String html=Reports.html(report);assertTrue(html.contains("\\u003c\\u003e\\u0026\\u2028\\u2029"));
    }
    @Test void outputFailurePreservesExistingDestinationForAllFormats() throws Exception {
        Path input=customMod(directory,"a",Map.of("schemaVersion",1,"id","a","description",randomText(1_200_000,"abcdefghijklmnopqrstuvwxyz0123456789"),"mixins",List.of("test.mixins.json")),
                Map.of("package","example","mixins",List.of("TestMixin")),"game/Player",new Method("Inject","attack()V"));
        for(String format:List.of("json","html","text")) {
            Path output=directory.resolve("report."+format);Files.writeString(output,"KEEP");StringWriter errors=new StringWriter();var command=Main.commandLine();command.setErr(new PrintWriter(errors));command.setOut(new PrintWriter(new StringWriter()));
            assertEquals(2,command.execute("scan",input.toString(),"--format",format,"--max-report-mib","1","--output",output.toString()));assertEquals("KEEP",Files.readString(output));assertTrue(errors.toString().contains("OUTPUT_ERROR"));assertTrue(errors.toString().contains("exceeds"));
        }
    }
    @Test void largeEvidenceHasAPreviewWhileRawAnnotationIsPreserved() throws Exception {
        String argument=randomText(10_000,"abcdefghijklmnopqrstuvwxyz0123456789");var point=new TreeMap<String,Object>(at("INVOKE","Lgame/Ops;hit()V",0));point.put("args",Collections.nCopies(100,argument));
        Report report=scan(options(mod(directory,"a",new Method("Redirect","attack()V",Map.of("at",point))),mod(directory,"b",new Method("Redirect","attack()V",Map.of("at",point)))));
        assertFalse(report.findings.isEmpty());assertTrue(report.findings.stream().flatMap(f->f.evidence.stream()).allMatch(e->e.length()<4096));assertTrue(report.findings.getFirst().evidence.getFirst().contains("see complete inventory"));
        assertEquals(100,Util.list(report.modifications.getFirst().at.getFirst().get("args")).size());assertEquals(argument,Util.list(report.modifications.getFirst().at.getFirst().get("args")).getFirst());
        assertTrue(Util.previewJson("あ".repeat(200),512).contains("exceeds"));
    }
}
