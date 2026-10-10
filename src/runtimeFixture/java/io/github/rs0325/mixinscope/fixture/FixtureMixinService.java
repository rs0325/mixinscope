package io.github.rs0325.mixinscope.fixture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/** Test-only Mixin host for authored fixtures. It is not part of the CLI or its classpath. */
public final class FixtureMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
    private final Set<String> invalid = new HashSet<>(), loaded = new HashSet<>();
    @Override public String getName() { return "MixinScope authored fixture"; }
    @Override public boolean isValid() { return true; }
    @Override public Phase getInitialPhase() { return Phase.DEFAULT; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return this; }
    @Override public IClassTracker getClassTracker() { return this; }
    @Override public IMixinAuditTrail getAuditTrail() { return null; }
    @Override public Collection<String> getPlatformAgents() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("mixinscope-authored-fixture"); }
    @Override public InputStream getResourceAsStream(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
    @Override public URL[] getClassPath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(path -> {
            try { return Path.of(path).toUri().toURL(); } catch (Exception e) { throw new IllegalStateException(e); }
        }).toArray(URL[]::new);
    }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        if (invalid.contains(name)) throw new ClassNotFoundException("Mixin class must not load directly: " + name);
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
    @Override public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, false, 0); }
    @Override public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
    @Override public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
        ClassNode node = new ClassNode(); new ClassReader(bytes(name)).accept(node, flags); return node;
    }
    public byte[] bytes(String name) throws ClassNotFoundException, IOException {
        try (InputStream stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new ClassNotFoundException(name); return stream.readAllBytes();
        }
    }
    @Override public Collection<ITransformer> getTransformers() { return List.of(); }
    @Override public Collection<ITransformer> getDelegatedTransformers() { return List.of(); }
    @Override public void addTransformerExclusion(String name) {}
    @Override public void registerInvalidClass(String name) { invalid.add(name); }
    @Override public boolean isClassLoaded(String name) { return loaded.contains(name); }
    @Override public String getClassRestrictions(String name) { return ""; }
    public void markLoaded(String name) { loaded.add(name); }
    public IMixinTransformer transformer() {
        IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
        if (factory == null) throw new IllegalStateException("Mixin transformer factory was not registered");
        return factory.createTransformer();
    }
}
