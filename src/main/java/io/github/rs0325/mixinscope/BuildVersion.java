package io.github.rs0325.mixinscope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.IVersionProvider;

/** VERSION is copied into the application resources by Gradle. */
public final class BuildVersion implements IVersionProvider {
    public static final String VERSION = read();
    private static String read() {
        try (var input = BuildVersion.class.getResourceAsStream("/mixinscope-version.txt")) {
            if (input == null) throw new IllegalStateException("Missing build version resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
    @Override public String[] getVersion() { return new String[]{"MixinScope " + VERSION}; }
}
