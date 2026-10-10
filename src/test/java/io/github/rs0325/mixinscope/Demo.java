package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.Reports;
import java.nio.file.*;
import static io.github.rs0325.mixinscope.Fixtures.*;

/** Generates self-authored examples only; no Minecraft or third-party JAR distribution. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args.length==0?"build/demo":args[0]);Path mods=directory.resolve("mods");
        Path a=mod(mods,"mod_a",new Method("Overwrite","attack()V"));
        Path b=mod(mods,"mod_b",new Method("Overwrite","attack()V"));
        Path c=mod(mods,"mod_c",new Method("Overwrite","getAttributeValue()D"));
        var options=options(a,b,c);options.gameJar=game(directory);Report report=scan(options);
        report.visibleFindingIds=report.findings.stream().map(f->f.id).toList();
        Files.writeString(directory.resolve("report.json"),Reports.json(report));
        Files.writeString(directory.resolve("report.txt"),Reports.text(report,report.findings));
        Files.writeString(directory.resolve("report.html"),Reports.html(report));
        report.findings.getFirst().reason="</script><script>globalThis.pwned=1</script>";
        Files.writeString(directory.resolve("escaped-report.html"),Reports.html(report));
        System.out.println("Generated self-authored demo: "+directory.toAbsolutePath());
    }
}
