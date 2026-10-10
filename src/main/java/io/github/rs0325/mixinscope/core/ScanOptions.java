package io.github.rs0325.mixinscope.core;

import java.nio.file.Path;
import java.util.*;

public final class ScanOptions {
    public List<Path> inputs = new ArrayList<>(), classpath = new ArrayList<>();
    public Path gameJar, mappings;
    public String loader = "auto", loaderVersion = "unknown", gameVersion = "unknown",
            side = "both", namespace = "unknown", outputNamespace, gameNamespace,
            mappingGameVersion, mixinVersion = "unknown", refmapContext;
    public String metadataProfile = "auto";
    public int targetJava = 21, maxPairs = 100_000;
    public long maxArchive = 128L << 20, maxEntry = 16L << 20, maxTotal = 256L << 20;
    public int maxDepth = 4, maxEntries = 50_000, maxRatio = 200;
    public long timeoutSeconds = 120;
    private long deadlineNanos = Long.MAX_VALUE;
    public void startBudget() { deadlineNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L; }
    public void checkBudget() { if (System.nanoTime() > deadlineNanos) throw new IllegalStateException("Analysis time limit exceeded"); }
}
