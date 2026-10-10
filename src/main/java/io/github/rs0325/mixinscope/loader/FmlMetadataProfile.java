package io.github.rs0325.mixinscope.loader;

import io.github.rs0325.mixinscope.model.Model.*;
import java.math.BigInteger;
import java.util.*;

/** Explicit source snapshot only. Custom loader dependencies and Maven qualifiers stay unknown. */
final class FmlMetadataProfile {
    static final String SOURCE="https://github.com/neoforged/FancyModLoader/tree/bcbdb3520249deba18d92de2fcbdcee550c75233";
    static final String MIXIN_ARTIFACT="0.17.1+mixin.0.8.7";
    private FmlMetadataProfile() {}
    static void evaluateBehavior(MixinConfig config, String raw, Report report) {
        config.registration.put("behaviorProfileSource", SOURCE);
        config.registration.put("behaviorMixinArtifact", MIXIN_ARTIFACT);
        config.registration.put("behaviorBounds", Map.of("minimum", "0.17.1", "maximumNumericRelease", "0.17.1"));
        if (raw.isEmpty()) {
            config.registration.put("behaviorStatus", "DEFAULT"); config.registration.put("behaviorCompatibility",17001); return;
        }
        if (raw.length()>128 || !raw.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            config.registration.put("behaviorStatus", "UNVERIFIED");
            if (config.applicability!=Applicability.INELIGIBLE) config.applicability=Applicability.UNKNOWN;
            report.diagnostic("BEHAVIOR_VERSION_UNVERIFIED",config.path,"Only numeric release behaviorVersion is compared; Maven qualifiers and custom loader dependencies are unverified",false);return;
        }
        String[] parts=raw.split("\\.");
        List<BigInteger> version=Arrays.stream(parts).map(BigInteger::new).toList(),bound=List.of(BigInteger.ZERO,BigInteger.valueOf(17),BigInteger.ONE);
        int comparison=0;for(int i=0;i<3&&comparison==0;i++)comparison=version.get(i).compareTo(bound.get(i));
        if(comparison!=0) {
            config.registration.put("behaviorStatus",comparison<0?"TOO_OLD":"TOO_NEW");config.applicability=Applicability.INELIGIBLE;
            report.diagnostic("BEHAVIOR_VERSION_OUT_OF_PROFILE",config.path,"behaviorVersion "+raw+" is outside the explicitly declared fixed FML snapshot's numeric bounds [0.17.1, 0.17.1]; installed runtime was not inspected",false);
        } else {config.registration.put("behaviorStatus","SUPPORTED_NUMERIC_RELEASE");config.registration.put("behaviorCompatibility",17001);}
    }
}
