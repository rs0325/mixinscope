package io.github.rs0325.mixinscope;

import io.github.rs0325.mixinscope.core.*;
import io.github.rs0325.mixinscope.model.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.rs0325.mixinscope.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class FmlProfileTest {
    @TempDir Path directory;
    private Path artifact(String behavior,String required,boolean neo) throws Exception {
        String toml="modLoader=\"javafml\"\n[[mods]]\nmodId=\"example\"\nversion=\"1\"\n[[mixins]]\nconfig=\"test.mixins.json\"\n"+behavior+required;
        return jar(directory.resolve("example.jar"),Map.of(neo?"META-INF/neoforge.mods.toml":"META-INF/mods.toml",toml.getBytes(StandardCharsets.UTF_8),
                "test.mixins.json",Util.JSON.writeValueAsBytes(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V"))));
    }
    private ScanOptions profile(Path... inputs) {var o=options(inputs);o.loader="neoforge";o.loaderVersion="26.1.0.5-beta";o.metadataProfile="fml-12.0";return o;}
    @Test void fixedSnapshotDefaultsAndNumericReleaseAreSeparateFromSpongeMixinVersion() throws Exception {
        for(String raw:List.of("","behaviorVersion=\"0.17.1\"\n")) {
            Report r=scan(profile(artifact(raw,"",true)));var c=r.configs.getFirst();
            assertEquals(Applicability.ELIGIBLE,c.applicability);assertEquals(17001,c.registration.get("behaviorCompatibility"));
            assertEquals("0.17.1+mixin.0.8.7",c.registration.get("behaviorMixinArtifact"));assertTrue(c.registration.get("behaviorProfileSource").toString().endsWith("bcbdb3520249deba18d92de2fcbdcee550c75233"));
        }
    }
    @Test void lowerAndUpperOutOfSnapshotBoundsCannotApply() throws Exception {
        for(String version:List.of("0.17.0","0.17.2","0.8.7")) {
            Report r=scan(profile(artifact("behaviorVersion=\""+version+"\"\n","",true)));
            assertEquals(Applicability.INELIGIBLE,r.configs.getFirst().applicability);assertTrue(diagnostic(r,"BEHAVIOR_VERSION_OUT_OF_PROFILE"));
        }
    }
    @Test void qualifiersDoNotBorrowNumericComparison() throws Exception {
        Report r=scan(profile(artifact("behaviorVersion=\"0.17.1-beta\"\n","",true)));assertEquals(Applicability.UNKNOWN,r.configs.getFirst().applicability);assertTrue(diagnostic(r,"BEHAVIOR_VERSION_UNVERIFIED"));
    }
    @Test void automaticLegacyProfileDoesNotApplyNewFmlBounds() throws Exception {
        var o=profile(artifact("behaviorVersion=\"0.17.0\"\n","",true));o.metadataProfile="auto";Report r=scan(o);
        assertEquals(Applicability.UNKNOWN,r.configs.getFirst().applicability);assertFalse(diagnostic(r,"BEHAVIOR_VERSION_OUT_OF_PROFILE"));assertTrue(diagnostic(r,"METADATA_CONDITION_PROFILE_UNKNOWN"));
    }
    @Test void forgeDoesNotBorrowTheNeoForgeProfile() throws Exception {
        var o=profile(artifact("behaviorVersion=\"0.17.0\"\n","requiredMods=[\"absent\"]\n",false));o.loader="forge";Report r=scan(o);
        assertEquals(Applicability.UNKNOWN,r.configs.getFirst().applicability);assertFalse(diagnostic(r,"REQUIRED_MOD_MISSING"));assertFalse(diagnostic(r,"BEHAVIOR_VERSION_OUT_OF_PROFILE"));
    }
    @Test void requiredBuiltInsComeFromTheDeclaredProfileAndOtherModsRemainCandidates() throws Exception {
        Report r=scan(profile(artifact("","requiredMods=[\"minecraft\",\"neoforge\"]\n",true)));assertEquals(Applicability.CONDITIONAL,r.configs.getFirst().applicability);assertFalse(diagnostic(r,"REQUIRED_MOD_MISSING"));
        r=scan(profile(artifact("","requiredMods=[\"other\"]\n",true),mod(directory,"other",new Method("Inject","attack()V"))));
        assertEquals(Applicability.CONDITIONAL,r.configs.stream().filter(c->c.registration.get("conditionProfile").equals("DECLARED:fml-12.0")).findFirst().orElseThrow().applicability);assertFalse(diagnostic(r,"REQUIRED_MOD_MISSING"));
    }
    @Test void missingRequiredModCannotBeAssertedAfterAReadFailure() throws Exception {
        Path broken=directory.resolve("broken.jar");Files.writeString(broken,"broken");Report r=scan(profile(artifact("","requiredMods=[\"absent\"]\n",true),broken));
        assertTrue(r.coverage.partial);assertNotEquals(Applicability.INELIGIBLE,r.configs.getFirst().applicability);assertFalse(diagnostic(r,"REQUIRED_MOD_MISSING"));
        assertEquals("UNKNOWN_PARTIAL_INPUT",r.configs.getFirst().registration.get("requiredModsStatus"));
    }
}
