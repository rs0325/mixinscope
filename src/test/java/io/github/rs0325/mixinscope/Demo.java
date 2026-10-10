package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.report.Reports;
import io.github.rs0325.mixinscope.core.Util;
import java.util.*;
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
        // Deliberately long unbroken labels for manual narrow-screen QA; authored data only.
        Report longLabels=Util.JSON.readValue(Reports.json(report).replace("game/Player","game/"+"LongTargetName".repeat(20)+"Player")
                .replace("mod_a","mod_a_"+"long_name_".repeat(30)),Report.class);
        Files.writeString(directory.resolve("long-label-report.json"),Reports.json(longLabels));
        Files.writeString(directory.resolve("long-label-report.html"),Reports.html(longLabels));
        Files.writeString(directory.resolve("empty-report.html"),Reports.html(new Report()));
        report.findings.getFirst().reason="</script><script>globalThis.pwned=1</script>";
        Files.writeString(directory.resolve("escaped-report.html"),Reports.html(report));
        Report stress=Util.JSON.readValue(Reports.json(report),Report.class);
        stress.findings=new ArrayList<>();stress.modifications=new ArrayList<>(stress.modifications);
        Finding template=report.findings.getLast();
        for(int i=0;i<253;i++) {
            Finding f=Util.JSON.readValue(Util.JSON.writeValueAsString(template),Finding.class);f.id="stress-"+i;f.suppressed=i<3;f.suppressionReason=f.suppressed?"Stress control":"";
            if(i==252)f.target=new TargetKey("other-version",f.target.loaderProfile(),f.target.namespace(),f.target.owner(),f.target.name(),f.target.descriptor());
            stress.findings.add(f);
        }
        for(int i=0;i<205;i++) {
            Modification m=Util.JSON.readValue(Util.JSON.writeValueAsString(report.modifications.getFirst()),Modification.class);m.id="inventory-"+i;stress.modifications.add(m);
        }
        Files.writeString(directory.resolve("html-stress-report.html"),Reports.html(stress));
        stress.displayFilter.put("mod","absent-mod");Files.writeString(directory.resolve("unknown-mod-report.html"),Reports.html(stress));
        System.out.println("Generated self-authored demo: "+directory.toAbsolutePath());
    }
}
