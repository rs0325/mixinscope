package io.github.rs0325.mixinscope;

import java.nio.file.*;
import static io.github.rs0325.mixinscope.Fixtures.*;

/** Reproducible synthetic scale checks; generation is excluded from scan time. */
public final class Benchmark {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("build/benchmark");Files.createDirectories(root);
        StringBuilder csv=new StringBuilder("artifacts,groups,findings,scan_ms,used_heap_mib,max_heap_mib,skipped_pairs\n");
        for(int count:new int[]{100,1000}) {
            Path dir=root.resolve(Integer.toString(count));
            for(int i=0;i<count;i++) mod(dir,"mod_"+i,"game/Owner"+(i%100),"*",new Method("Inject","attack()V"));
            long start=System.nanoTime();var report=scan(options(dir));long ms=(System.nanoTime()-start)/1_000_000;
            int expected=count==100?0:4500;
            if(report.findings.size()!=expected||report.coverage.partial)throw new AssertionError("Incorrect scale result");
            long used=(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/(1L<<20);
            csv.append(count).append(",100,").append(report.findings.size()).append(',').append(ms).append(',').append(used).append(',').append(Runtime.getRuntime().maxMemory()/(1L<<20)).append(',').append(report.coverage.skippedPairs).append('\n');
            if(ms>30_000)throw new AssertionError("Synthetic pack exceeds 30-second budget");
        }
        Files.writeString(root.resolve("results.csv"),csv);System.out.print(csv);
    }
}
