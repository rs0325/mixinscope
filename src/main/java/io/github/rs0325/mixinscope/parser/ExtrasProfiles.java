package io.github.rs0325.mixinscope.parser;

import io.github.rs0325.mixinscope.model.Model.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Profiles pinned to upstream tags. A declared version is not evidence of loader selection. */
public final class ExtrasProfiles {
    private ExtrasProfiles() {}
    private static final Map<String, String> COMMITS = Map.of(
            "0.3.2", "f73ce6f262c06a8ef686ca5517eb3020d59749bb",
            "0.4.1", "ca7ec9887cadf5d4e0c6ee62fd29291e9a077d06",
            "0.5.0", "73e5b74a55e7de77b084c5a4230eac0c5937c547");
    public static boolean known(String version) { return COMMITS.containsKey(version); }
    public static void describe(Modification modification, MethodNode method, String version, Report report) {
        boolean known = known(version);
        boolean available = !version.equals("0.3.2") || !modification.kind.equals("WrapMethod") && !modification.annotationDescriptor.contains("/v2/");
        modification.defaults.put("extrasProfile", known ? "DECLARED:MixinExtras-" + version : "UNVERIFIED:MixinExtras-" + version);
        if (known) modification.defaults.put("extrasProfileSource", "https://github.com/LlamaLad7/MixinExtras/tree/" + COMMITS.get(version));
        String stage = "UNKNOWN";
        if (known && available) stage = switch (modification.kind) {
            case "WrapOperation" -> "POST_APPLY_WRAP_OPERATION";
            case "WrapMethod" -> "POST_APPLY_WRAP_METHOD";
            case "WrapWithCondition" -> modification.annotationDescriptor.contains("/v2/") ? "POST_APPLY_WRAP_WITH_CONDITION" : "NORMAL_INJECTION";
            default -> "NORMAL_INJECTION";
        };
        modification.defaults.put("applicationStage", stage);
        if (known && !available) report.diagnostic("EXTRAS_KIND_UNAVAILABLE", modification.mixin + "." + modification.handler,
                modification.annotationDescriptor + " is absent from declared MixinExtras " + version + "; declaration is retained but its applicability is unknown", false);
        if (known && !available && modification.applicability != Applicability.INELIGIBLE) modification.applicability = Applicability.UNKNOWN;
        if (modification.kind.equals("WrapOperation") || modification.kind.equals("WrapMethod")) {
            int calls = 0;
            for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call &&
                    call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation") && call.name.equals("call") &&
                    call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) calls++;
            String evidence = method.instructions.size() == 0 ? "BODY_UNAVAILABLE" : calls == 0 ? "NO_DIRECT_OPERATION_CALL" : "DIRECT_OPERATION_CALL_PRESENT";
            modification.handlerAnalysis.put("operationCallEvidence", evidence);
            modification.handlerAnalysis.put("directOperationCallCount", calls);
            modification.handlerAnalysis.put("limitation", "Direct call syntax only. Delegation, reachable paths, call arguments, call multiplicity at runtime and compatibility are not determined.");
            if (calls == 0) report.diagnostic("WRAPPER_CALL_UNVERIFIED", modification.mixin + "." + modification.handler,
                    "No direct Operation.call in this handler; it may delegate to a helper or bypass the operation. This does not prove runtime bypass", false);
        }
    }
}
