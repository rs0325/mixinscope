package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Conservative matching on original bytecode; unsupported points never become verified. */
public final class InstructionMatcher {
    private InstructionMatcher() {}
    public static void resolve(MethodNode method, Modification modification, Target target, TinyMappings mappings, UnaryOperator<String> references, Report report,Runnable checkBudget) {
        checkBudget.run();
        List<AbstractInsnNode> code = new ArrayList<>();
        int visited=0;
        for (AbstractInsnNode instruction : method.instructions) {if((++visited&1023)==0)checkBudget.run();if (instruction.getOpcode() >= 0) code.add(instruction);}
        if (code.isEmpty()) { report.diagnostic("REFERENCE_BODY_UNAVAILABLE", modification.id, "Method has no reference instruction body", false); return; }
        if (modification.kind.equals("Overwrite") || modification.kind.equals("WrapMethod")) return;
        if (modification.at.isEmpty()) { report.diagnostic("INJECTION_POINT_MISSING", modification.id, "No supported @At metadata", false); return; }
        if(!modification.pointCandidatesNormalized)return;
        Set<Integer> matches = new TreeSet<>();
        try {
            for (Map<String, Object> at : modification.normalizedAt) {
                checkBudget.run();
                String sliceId = Util.str(at.get("slice"), "");
                int low = 0, high = code.size() - 1;
                List<Map<String, Object>> slices = modification.normalizedSlices;
                Map<String, Object> slice = slices.stream().filter(s -> Util.str(s.get("id"), "").equals(sliceId)).findFirst().orElse(Map.of());
                if (!sliceId.isEmpty() && slice.isEmpty()) throw new IllegalArgumentException("Unknown slice: " + sliceId);
                if (!slice.isEmpty()) {
                    low = boundary(Util.map(slice.get("from")), code, 0, modification, null, UnaryOperator.identity(),checkBudget);
                    high = boundary(Util.map(slice.get("to")), code, code.size() - 1, modification, null, UnaryOperator.identity(),checkBudget);
                    if (low > high) throw new IllegalArgumentException("Invalid slice boundaries");
                }
                for (int index : point(at, code, low, high, modification, null, UnaryOperator.identity(),checkBudget)) matches.add(index);
            }
            target.instructions = new ArrayList<>(matches); target.instructionsVerified = true;
            if (matches.isEmpty()) report.diagnostic("INJECTION_POINT_NO_MATCH", modification.id,
                    "No matching injection instruction in the original reference bytecode; runtime application is unverified", false);
            int require = Util.integer(modification.defaults.get("require"), 0), allow = Util.integer(modification.options.get("allow"), -1);
            if (matches.size() < require || allow >= Math.max(1, require) && matches.size() > allow)
                report.diagnostic("INJECTION_COUNT_CANDIDATE", modification.id,
                        "Reference matches=" + matches.size() + ", require=" + require + ", allow=" + allow + "; runtime failure is not determined", false);
            if (modification.options.containsKey("expect")) report.diagnostic("EXPECT_DEBUG_DEPENDENT", modification.id, "expect is retained; its enforcement depends on Mixin debug settings", false);
            if (modification.options.containsKey("constraints") || modification.options.keySet().stream().anyMatch(k -> k.endsWith("/Group;")))
                report.diagnostic("INJECTOR_CONSTRAINT_UNKNOWN", modification.id, "Runtime/group constraints are not evaluated", false);
        } catch (IllegalArgumentException ex) {
            report.diagnostic("INJECTION_POINT_UNSUPPORTED", modification.id, ex.getMessage(), false);
        }
    }
    private static int boundary(Map<String, Object> at, List<AbstractInsnNode> code, int fallback, Modification m, TinyMappings mappings, UnaryOperator<String> references,Runnable checkBudget) {
        if (at.isEmpty()) return fallback;
        List<Integer> result = point(at, code, 0, code.size() - 1, m, mappings, references,checkBudget);
        if (result.size() != 1) throw new IllegalArgumentException("Slice boundary is not unique");
        return result.getFirst();
    }
    private static List<Integer> point(Map<String, Object> at, List<AbstractInsnNode> code, int low, int high, Modification m, TinyMappings mappings, UnaryOperator<String> references,Runnable checkBudget) {
        PointValidation.validate(at);
        String kind = Util.str(at.get("value"), "");
        if (!Util.list(at.get("args")).isEmpty()) throw new IllegalArgumentException("Injection-point args are not supported: " + kind);
        String rawTarget = Util.str(at.get("target"), "");
        if (Util.bool(m.options.get("remap"), true) && Util.bool(at.get("remap"), true)) rawTarget = references.apply(rawTarget);
        boolean remap = mappings != null && Util.bool(m.options.get("remap"), true) && Util.bool(at.get("remap"), true);
        int opcode = Util.integer(at.get("opcode"), -1);
        if (kind.equals("INVOKE")) {
            TargetResolver.Selector selector = TargetResolver.parse(rawTarget, "");
            if (selector.owner().isEmpty() || selector.descriptor().isEmpty() || selector.name().contains("*")) throw new IllegalArgumentException("INVOKE requires exact owner, name and descriptor");
        }
        if (kind.equals("FIELD")) {
            int end = rawTarget.indexOf(';'), colon = rawTarget.indexOf(':');
            if (!rawTarget.startsWith("L") || end <= 1 || colon <= end + 1 || colon == rawTarget.length() - 1) throw new IllegalArgumentException("FIELD requires Lowner;name:descriptor");
            if(!TargetResolver.validFieldDescriptor(rawTarget.substring(colon+1)))throw new IllegalArgumentException("Invalid FIELD descriptor");
        }
        if (kind.equals("NEW") && (rawTarget.isEmpty() || rawTarget.contains("("))) throw new IllegalArgumentException("NEW constructor-descriptor queries are unsupported; use class owner");
        List<Integer> matches = new ArrayList<>();
        for (int i = Math.max(0, low); i <= high && i < code.size(); i++) {
            if((i&1023)==0)checkBudget.run();
            AbstractInsnNode instruction = code.get(i); int op = instruction.getOpcode();
            boolean match = switch (kind) {
                case "HEAD" -> i == low;
                case "RETURN", "TAIL" -> op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
                case "INVOKE" -> {
                    if (rawTarget.isEmpty()) throw new IllegalArgumentException("INVOKE target is missing");
                    if (!(instruction instanceof MethodInsnNode call)) yield false;
                    TargetResolver.Selector selector = TargetResolver.parse(rawTarget, "");
                    if (selector.owner().isEmpty() || selector.descriptor().isEmpty() || selector.name().contains("*")) throw new IllegalArgumentException("INVOKE requires exact owner, name and descriptor");
                    String owner = selector.owner(), name = selector.name(), desc = selector.descriptor();
                    if (remap) { name = mappings.method(owner, name, desc); owner = mappings.owner(owner); desc = mappings.descriptor(desc); }
                    yield call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);
                }
                case "FIELD" -> {
                    if (!(instruction instanceof FieldInsnNode field)) yield false;
                    int end = rawTarget.indexOf(';'), colon = rawTarget.indexOf(':');
                    if (!rawTarget.startsWith("L") || end < 0 || colon < end) throw new IllegalArgumentException("FIELD requires Lowner;name:descriptor");
                    String owner = rawTarget.substring(1, end), name = rawTarget.substring(end + 1, colon), desc = rawTarget.substring(colon + 1);
                    if (remap) { name = mappings.field(owner, name, desc); owner = mappings.owner(owner); desc = mappings.descriptor(desc); }
                    yield field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(desc);
                }
                case "NEW" -> {
                    if (!(instruction instanceof TypeInsnNode type) || op != Opcodes.NEW) yield false;
                    if (rawTarget.isEmpty() || rawTarget.contains("(")) throw new IllegalArgumentException("NEW constructor-descriptor queries are unsupported; use class owner");
                    String owner = rawTarget.startsWith("L") && rawTarget.endsWith(";") ? rawTarget.substring(1, rawTarget.length() - 1) : Util.owner(rawTarget);
                    yield type.desc.equals(remap ? mappings.owner(owner) : owner);
                }
                case "CONSTANT" -> constant(at, instruction);
                default -> throw new IllegalArgumentException("Unsupported injection point: " + kind);
            };
            if (match && (opcode < 0 || op == opcode)) matches.add(i);
        }
        if (kind.equals("TAIL") && !matches.isEmpty()) matches = new ArrayList<>(List.of(matches.getLast()));
        int ordinal = Util.integer(at.get("ordinal"), -1);
        if (ordinal < -1) throw new IllegalArgumentException("Invalid ordinal");
        if (ordinal >= 0) matches = ordinal < matches.size() ? new ArrayList<>(List.of(matches.get(ordinal))) : new ArrayList<>();
        String shift = Util.str(at.get("shift"), "NONE");
        int by = switch (shift) { case "NONE" -> 0; case "BEFORE" -> -1; case "AFTER" -> 1; case "BY" -> Util.integer(at.get("by"), 0); default -> throw new IllegalArgumentException("Unknown shift"); };
        List<Integer> shifted = new ArrayList<>();
        for (int i : matches) {
            checkBudget.run();
            AbstractInsnNode cursor = code.get(i);
            for (long step = 0; step < Math.abs((long) by); step++) {
                if((step&1023)==0)checkBudget.run();
                cursor = by < 0 ? cursor.getPrevious() : cursor.getNext();
                if (cursor == null) throw new IllegalArgumentException("Shift exceeds instruction bounds");
                if (cursor.getOpcode() < 0) throw new IllegalArgumentException("Shift crosses a bytecode label; raw InsnList position is not verified");
            }
            long position = (long) i + by;
            if (position < 0 || position >= code.size()) throw new IllegalArgumentException("Shift exceeds instruction bounds");
            shifted.add((int) position);
        }
        return shifted;
    }
    private static boolean constant(Map<String, Object> at, AbstractInsnNode instruction) {
        List<Object> constants = Util.list(at.get("constant"));
        if (constants.isEmpty()) throw new IllegalArgumentException("CONSTANT without explicit @Constant unsupported");
        Object value = constantValue(instruction);
        if (value == NO_CONSTANT) return false;
        for (Object item : constants) {
            Map<String, Object> c = Util.map(item);
            if (c.containsKey("expandZeroConditions") || c.containsKey("ordinal") || c.containsKey("slice")) throw new IllegalArgumentException("Constant expansion / per-constant ordinal / slice unsupported");
            if (Boolean.TRUE.equals(c.get("nullValue")) && value == null) return true;
            for (String key : List.of("intValue", "longValue", "floatValue", "doubleValue", "stringValue", "classValue"))
                if (c.containsKey(key) && Objects.equals(c.get(key), value)) return true;
        }
        return false;
    }
    private static final Object NO_CONSTANT = new Object();
    private static Object constantValue(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc) return ldc.cst instanceof Type type ? type.getDescriptor() : ldc.cst;
        if (instruction instanceof IntInsnNode n && (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.SIPUSH)) return n.operand;
        int op = instruction.getOpcode();
        if (op == Opcodes.ACONST_NULL) return null;
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return (long) (op - Opcodes.LCONST_0);
        if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (float) (op - Opcodes.FCONST_0);
        if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (double) (op - Opcodes.DCONST_0);
        return NO_CONSTANT;
    }
}
