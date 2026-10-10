package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class Fixtures {
    record Method(String kind, String selector, Map<String, Object> options, boolean visible) {
        Method(String kind, String selector) { this(kind, selector, Map.of(), false); }
        Method(String kind, String selector, Map<String, Object> options) { this(kind, selector, options, false); }
    }
    static Map<String, Object> at(String kind) { return Map.of("annotationType", "Lorg/spongepowered/asm/mixin/injection/At;", "value", kind); }
    static Map<String, Object> at(String kind, String target, int ordinal) {
        return Map.of("annotationType", "Lorg/spongepowered/asm/mixin/injection/At;", "value", kind, "target", target, "ordinal", ordinal);
    }
    static Path mod(Path directory, String id, String owner, String side, Method... methods) throws IOException {
        Map<String, Object> metadata = new TreeMap<>(Map.of("schemaVersion", 1, "id", id, "version", "1.0", "name", id, "environment", side, "mixins", List.of("test.mixins.json")));
        return customMod(directory, id, metadata, Map.of("package", "example", "mixins", List.of("TestMixin")), owner, methods);
    }
    static Path mod(Path directory, String id, Method... methods) throws IOException { return mod(directory, id, "game/Player", "*", methods); }
    static Path customMod(Path directory, String id, Map<String, Object> metadata, Map<String, Object> config, String owner, Method... methods) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>(); entries.put("fabric.mod.json", Util.JSON.writeValueAsBytes(metadata));
        entries.put("test.mixins.json", Util.JSON.writeValueAsBytes(config)); entries.put("example/TestMixin.class", mixin(owner, methods));
        return jar(directory.resolve(id + ".jar"), entries);
    }
    static byte[] mixin(String owner, Method... specs) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/TestMixin", null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = annotation.visitArray(owner.startsWith("string:") ? "targets" : "value");
        for (String target : owner.replaceFirst("^string:", "").split(",")) value.visit(null, owner.startsWith("string:") ? target : Type.getObjectType(target));
        value.visitEnd(); annotation.visitEnd();
        for (int i = 0; i < specs.length; i++) {
            Method spec = specs[i]; String name = "handler" + i, descriptor = "()V";
            if (spec.kind.equals("Overwrite")) { int paren = spec.selector.indexOf('('); name = spec.selector.substring(0, paren); descriptor = spec.selector.substring(paren); }
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
            String desc = switch (spec.kind) {
                case "Overwrite", "Shadow", "Unique", "Intrinsic" -> "Lorg/spongepowered/asm/mixin/" + spec.kind + ";";
                case "Accessor", "Invoker" -> "Lorg/spongepowered/asm/mixin/gen/" + spec.kind + ";";
                case "WrapOperation" -> "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
                case "WrapMethod" -> "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
                case "WrapWithConditionV2" -> "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;";
                case "ModifyExpressionValue", "ModifyReturnValue", "WrapWithCondition" -> "Lcom/llamalad7/mixinextras/injector/" + spec.kind + ";";
                default -> "Lorg/spongepowered/asm/mixin/injection/" + spec.kind + ";";
            };
            AnnotationVisitor injector = method.visitAnnotation(desc, spec.visible);
            if (!spec.kind.equals("Overwrite") && !spec.selector.isEmpty()) visit(injector, "method", List.of(spec.selector));
            if (!spec.kind.equals("Overwrite") && !spec.kind.equals("WrapMethod") && !spec.options.containsKey("at")) visit(injector, "at", at("HEAD"));
            for (var entry : spec.options.entrySet()) visit(injector, entry.getKey(), entry.getValue());
            injector.visitEnd(); method.visitCode();
            switch (Type.getReturnType(descriptor).getSort()) {
                case Type.VOID -> method.visitInsn(Opcodes.RETURN);
                case Type.DOUBLE -> { method.visitInsn(Opcodes.DCONST_0); method.visitInsn(Opcodes.DRETURN); }
                default -> { method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN); }
            }
            method.visitMaxs(0, 0); method.visitEnd();
        }
        // This initializer must never run during a scan.
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode(); init.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError"); init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false); init.visitInsn(Opcodes.ATHROW);
        init.visitMaxs(0, 0); init.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void visit(AnnotationVisitor av, String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            String type = Objects.toString(map.get("annotationType"), "Lorg/spongepowered/asm/mixin/injection/At;");
            AnnotationVisitor child = av.visitAnnotation(key, type);
            for (var e : map.entrySet()) if (!e.getKey().equals("annotationType")) visit(child, e.getKey().toString(), e.getValue());
            child.visitEnd();
        } else if (value instanceof List<?> list) { AnnotationVisitor child = av.visitArray(key); for (Object item : list) visit(child, null, item); child.visitEnd(); }
        else if (value instanceof String[] enumValue) av.visitEnum(key, enumValue[0], enumValue[1]);
        else av.visit(key, value);
    }
    static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : new TreeMap<>(entries).entrySet()) { ZipEntry entry = new ZipEntry(e.getKey()); entry.setTime(0); zip.putNextEntry(entry); zip.write(e.getValue()); zip.closeEntry(); }
        }
        return file;
    }
    static Path game(Path directory) throws IOException { return jar(directory.resolve("game.jar"), Map.of("game/Player.class", gameClass("game/Player", "java/lang/Object"))); }
    static byte[] gameClass(String owner, String parent) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, parent, null);
        for (String desc : List.of("()V", "(I)V")) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "attack", desc, null, null); method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "game/Ops", "hit", "()V", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "game/Ops", "hit", "()V", false);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        MethodVisitor attribute = writer.visitMethod(Opcodes.ACC_PUBLIC, "getAttributeValue", "()D", null, null);
        attribute.visitCode(); attribute.visitInsn(Opcodes.DCONST_0); attribute.visitInsn(Opcodes.DRETURN); attribute.visitMaxs(0, 0); attribute.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static ScanOptions options(Path... inputs) {
        ScanOptions o = new ScanOptions(); o.inputs = Arrays.asList(inputs); o.gameVersion = "1.21.1"; o.namespace = "named";
        o.loader = "fabric"; o.loaderVersion = "0.16.14"; o.mixinVersion = "0.8.7"; o.mixinExtrasVersion = "0.4.1"; return o;
    }
    static Report scan(ScanOptions options) { return new io.github.rs0325.mixinscope.core.Scanner().scan(options); }
    static Set<String> rules(Report report) { return new TreeSet<>(report.findings.stream().map(f -> f.rule).toList()); }
    static boolean diagnostic(Report report, String code) { return report.coverage.diagnostics.stream().anyMatch(d -> d.code().equals(code)); }
}
