package io.github.rs0325.mixinscope.fixture;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import io.github.rs0325.mixinscope.core.Util;
import java.nio.file.*;
import java.util.*;

/** Executes only compiled repository fixtures in a separate JVM. Accepts no external input. */
public final class FixtureIntegration {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("The fixture harness accepts no external input");
        MixinBootstrap.init();
        if (!(MixinService.getService() instanceof FixtureMixinService service)) throw new IllegalStateException("Wrong test Mixin service");
        IMixinTransformer transformer = service.transformer();
        MixinExtrasBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("fixture.mixins.json");
        ClassLoader loader = new ClassLoader(FixtureIntegration.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("io.github.rs0325.mixinscope.fixture.target.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> defined = findLoadedClass(name);
                    if (defined == null) {
                        try {
                            byte[] original = service.bytes(name), transformed = transformer.transformClassBytes(name, name, original);
                            defined = defineClass(name, transformed, 0, transformed.length); service.markLoaded(name);
                        } catch (Exception e) { throw new ClassNotFoundException(name, e); }
                    }
                    if (resolve) resolveClass(defined); return defined;
                }
            }
        };
        Map<String, Object> cases = new TreeMap<>();
        for (String name : List.of("ChainCalculator", "BypassCalculator", "MethodCalculator", "DelegatedCalculator")) {
            Class<?> target = loader.loadClass("io.github.rs0325.mixinscope.fixture.target." + name);
            int value = (Integer) target.getMethod("compute", int.class).invoke(null, 3);
            int calls = target.getField("calls").getInt(null);
            if (name.equals("BypassCalculator")) {
                if (calls != 0 || !Set.of(99,101).contains(value)) throw new AssertionError("Bypass control did not bypass the original operation: " + value + " / " + calls);
            } else if (calls != 1 || value != (name.equals("DelegatedCalculator") ? 7 : 9)) {
                throw new AssertionError("Wrapper chain not applied to " + name + ": " + value + " / " + calls);
            }
            cases.put(name, Map.of("result", value, "originalCalls", calls));
        }
        Class<?> safe = loader.loadClass("io.github.rs0325.mixinscope.fixture.target.SafeCalculator");
        int safeValue = (Integer) safe.getMethod("compute", int.class).invoke(null, 3);
        if (safeValue != 6 || safe.getField("calls").getInt(null) != 1 || safe.getField("observations").getInt(null) != 2)
            throw new AssertionError("Ordinary non-cancellable Inject coexistence control failed");
        cases.put("SafeCalculator", Map.of("result", safeValue, "originalCalls", 1, "callbacks", 2));
        Class<?> overwrite = loader.loadClass("io.github.rs0325.mixinscope.fixture.target.OverwriteCalculator");
        int overwritten = (Integer) overwrite.getMethod("compute", int.class).invoke(null, 3);
        if (!Set.of(11, 22).contains(overwritten) || overwrite.getField("calls").getInt(null) != 0)
            throw new AssertionError("Overwrite replacement control failed");
        cases.put("OverwriteCalculator", Map.of("result", overwritten, "originalCalls", 0));
        Class<?> argument = loader.loadClass("io.github.rs0325.mixinscope.fixture.target.ArgumentCalculator");
        int argumentValue = (Integer) argument.getMethod("compute", int.class).invoke(null, 3);
        if (!Set.of(14, 16).contains(argumentValue) || argument.getField("calls").getInt(null) != 1)
            throw new AssertionError("Order-dependent argument modification control failed");
        cases.put("ArgumentCalculator", Map.of("result", argumentValue, "originalCalls", 1));
        try {
            loader.loadClass("io.github.rs0325.mixinscope.fixture.target.RedirectCalculator");
            throw new AssertionError("Two required redirects to one operation unexpectedly applied");
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause != null && !cause.getClass().getName().equals("org.spongepowered.asm.mixin.injection.throwables.InjectionError")) cause = cause.getCause();
            if (cause == null || !String.valueOf(cause.getMessage()).contains("failed injection check")) throw error;
            cases.put("RedirectCalculator", Map.of("expectedFailure", cause.getClass().getName(), "message", String.valueOf(cause.getMessage())));
        }
        Map<String, Object> results = Map.of("mixin", "0.15.5+mixin.0.8.7", "mixinExtras", "0.4.1", "scope", "Authored Java fixtures only; no Minecraft or downloaded mods executed", "cases", cases);
        Path output = Path.of("build/runtime-fixture/results.json"); Files.createDirectories(output.getParent());
        Files.writeString(output, Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results) + "\n");
        System.out.println("Authored Mixin runtime fixture checks passed: " + cases);
    }
}
