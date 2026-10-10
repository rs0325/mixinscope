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

class ArchiveLoaderTest {
    @TempDir Path directory;
    private byte[] json(Object value) throws Exception { return Util.JSON.writeValueAsBytes(value); }
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void traversalIsRejectedAndOtherInputsContinue() throws Exception {
        Path bad=jar(directory.resolve("bad.jar"),Map.of("../outside.class",new byte[]{1}));
        Report report=scan(options(bad,mod(directory,"good",new Method("Inject","attack()V"))));
        assertTrue(report.coverage.partial);assertEquals(1,report.modifications.size());assertTrue(diagnostic(report,"ARCHIVE_ERROR"));
    }
    @Test void compressionExpansionIsLimited() throws Exception {
        Path zip=jar(directory.resolve("bomb.jar"),Map.of("big.txt",new byte[1_000_000]));
        ScanOptions o=options(zip);o.maxRatio=10;assertTrue(scan(o).coverage.partial);
    }
    @Test void totalExpansionLimitIsEnforced() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V")));o.maxTotal=10;
        assertTrue(scan(o).coverage.partial);
    }
    @Test void archiveAndEntryLimitsAreEnforced() throws Exception {
        Path a=mod(directory,"a",new Method("Inject","attack()V"));ScanOptions o=options(a);o.maxArchive=10;assertTrue(scan(o).coverage.partial);
        o=options(a);o.maxEntry=10;assertTrue(scan(o).coverage.partial);
    }
    @Test void entryCountLimitIsEnforced() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Inject","attack()V")));o.maxEntries=1;assertTrue(scan(o).coverage.partial);
    }
    @Test void nestedDuplicateLibraryKeepsTwoParents() throws Exception {
        Path lib=mod(directory.resolve("lib"),"lib",new Method("Inject","attack()V"));byte[] child=Files.readAllBytes(lib);
        Map<String,byte[]> entriesA=new TreeMap<>();entriesA.put("fabric.mod.json",json(Map.of("schemaVersion",1,"id","a","jars",List.of(Map.of("file","nested/lib.jar")))));entriesA.put("nested/lib.jar",child);
        Map<String,byte[]> entriesB=new TreeMap<>(entriesA);entriesB.put("fabric.mod.json",json(Map.of("schemaVersion",1,"id","b","jars",List.of(Map.of("file","nested/lib.jar")))));
        Report report=scan(options(jar(directory.resolve("a.jar"),entriesA),jar(directory.resolve("b.jar"),entriesB)));
        assertEquals(3,report.artifacts.size());assertEquals(1,report.modifications.size());
        Artifact nested=report.artifacts.stream().filter(a->a.nested).findFirst().orElseThrow();assertEquals(2,nested.origins.size());assertTrue(diagnostic(report,"NESTED_SELECTION_UNKNOWN"));
    }
    @Test void missingNestedJarAndDepthLimitArePartial() throws Exception {
        Path parent=jar(directory.resolve("parent.jar"),Map.of("fabric.mod.json",json(Map.of("schemaVersion",1,"id","p","jars",List.of(Map.of("file","lib.jar"))))));
        assertTrue(diagnostic(scan(options(parent)),"NESTED_ERROR"));
        Path child=mod(directory.resolve("child"),"lib",new Method("Inject","attack()V"));
        parent=jar(directory.resolve("parent2.jar"),Map.of("fabric.mod.json",json(Map.of("schemaVersion",1,"id","p","jars",List.of(Map.of("file","lib.jar")))),"lib.jar",Files.readAllBytes(child)));
        ScanOptions o=options(parent);o.maxDepth=0;assertTrue(diagnostic(scan(o),"NESTED_ERROR"));
    }
    @Test void classMajorUnsupportedIsExplicit() throws Exception {
        byte[] classBytes=mixin("game/Player",new Method("Inject","attack()V"));classBytes[6]=0;classBytes[7]=100;
        Path a=jar(directory.resolve("a.jar"),Map.of("fabric.mod.json",json(Map.of("schemaVersion",1,"id","a","mixins",List.of("test.mixins.json"))),"test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",classBytes));
        Report report=scan(options(a));assertTrue(diagnostic(report,"CLASS_UNSUPPORTED_OR_INVALID"));assertTrue(report.coverage.partial);
    }
    @Test void unknownInjectorIsNotSilentlyIgnored() throws Exception {
        Report report=scan(options(mod(directory,"a",new Method("CustomInjector","attack()V"))));assertTrue(diagnostic(report,"ANNOTATION_UNSUPPORTED"));
    }
    @Test void brokenZipAndMalformedJsonArePartial() throws Exception {
        Path bad=directory.resolve("bad.jar");Files.writeString(bad,"not a zip");assertTrue(scan(options(bad)).coverage.partial);
        Path malformed=jar(directory.resolve("malformed.jar"),Map.of("fabric.mod.json",bytes("{bad}")));assertTrue(diagnostic(scan(options(malformed)),"METADATA_ERROR"));
    }
    @Test void duplicateZipEntriesAreRejected() throws Exception {
        Path file=directory.resolve("duplicate.jar");
        try(var out=new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file)){
            for(int i=0;i<2;i++){var e=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("same.txt");out.putArchiveEntry(e);out.write(bytes("hello"));out.closeArchiveEntry();}
        }
        assertTrue(scan(options(file)).coverage.partial);
    }
    @Test void zipSymlinksAreRejected() throws Exception {
        Path file=directory.resolve("symlink.jar");
        try(var out=new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file)){
            var e=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("link");e.setUnixMode(0120777);out.putArchiveEntry(e);out.write(bytes("outside"));out.closeArchiveEntry();
        }
        assertTrue(scan(options(file)).coverage.partial);
    }
    @Test void multireleaseSelectionUsesDeclaredTargetJava() throws Exception {
        Path file=jar(directory.resolve("mr.jar"),Map.of("META-INF/MANIFEST.MF",bytes("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"),
                "fabric.mod.json",json(Map.of("schemaVersion",1,"id","mr","mixins",List.of("test.mixins.json"))),"test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"))),
                "example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V")),"META-INF/versions/21/example/TestMixin.class",mixin("game/Player",new Method("Inject","getAttributeValue()D"))));
        ScanOptions o=options(file);o.targetJava=17;assertEquals("attack()V",scan(o).modifications.getFirst().selectors.getFirst());
        o.targetJava=21;assertEquals("getAttributeValue()D",scan(o).modifications.getFirst().selectors.getFirst());
    }
    @Test void forgeManifestAndMultiModOwnership() throws Exception {
        Path file=jar(directory.resolve("forge.jar"),Map.of("META-INF/mods.toml",bytes("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"first\"\nversion=\"1\"\n[[mods]]\nmodId=\"second\"\nversion=\"1\"\n"),
                "META-INF/MANIFEST.MF",bytes("Manifest-Version: 1.0\r\nMixinConfigs: test.mixins.json\r\n\r\n"),"test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V"))));
        ScanOptions o=options(file);o.loader="forge";o.loaderVersion="47.3.0";Report report=scan(o);
        assertEquals(2,report.artifacts.getFirst().mods.size());assertEquals(1,report.modifications.size());assertTrue(diagnostic(report,"OWNERSHIP_AMBIGUOUS"));
    }
    @Test void neoForgeRequiredModAbsenceIsIneligible() throws Exception {
        Path file=jar(directory.resolve("neo.jar"),Map.of("META-INF/neoforge.mods.toml",bytes("modLoader=\"javafml\"\n[[mods]]\nmodId=\"neo\"\nversion=\"1\"\n[[mixins]]\nconfig=\"test.mixins.json\"\nrequiredMods=[\"sodium\"]\n"),"test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V"))));
        ScanOptions o=options(file);o.loader="neoforge";o.loaderVersion="21.1.200";Report legacy=scan(o);
        assertEquals(Applicability.UNKNOWN,legacy.modifications.getFirst().applicability);assertTrue(diagnostic(legacy,"METADATA_CONDITION_PROFILE_UNKNOWN"));
        o.metadataProfile="fml-12.0";Report report=scan(o);
        assertEquals(Applicability.INELIGIBLE,report.modifications.getFirst().applicability);assertTrue(diagnostic(report,"REQUIRED_MOD_MISSING"));
        assertNotNull(Util.JSON.readTree(io.github.rs0325.mixinscope.report.Reports.json(report)).get("artifacts"));
    }
    @Test void quiltSideAndAccessWidenerMetadata() throws Exception {
        Path file=jar(directory.resolve("quilt.jar"),Map.of("quilt.mod.json",json(Map.of("schema_version",1,"quilt_loader",Map.of("id","q","version","1"),"minecraft",Map.of("environment","dedicated_server"),"mixin","test.mixins.json","access_widener","q.accesswidener")),
                "test.mixins.json",json(Map.of("package","example","mixins",List.of("TestMixin"))),"example/TestMixin.class",mixin("game/Player",new Method("Inject","attack()V")),"q.accesswidener",bytes("accessWidener v2 named\n")));
        ScanOptions o=options(file);o.loader="quilt";o.loaderVersion="0.26.3";Report report=scan(o);
        assertEquals(Set.of("server"),report.modifications.getFirst().sides);assertFalse(report.transformers.isEmpty());
    }
    @Test void mismatchedLoaderDoesNotCoapply() throws Exception {
        ScanOptions o=options(mod(directory,"a",new Method("Overwrite","attack()V")),mod(directory,"b",new Method("Overwrite","attack()V")));o.loader="forge";
        Report report=scan(o);assertTrue(report.findings.isEmpty());assertTrue(diagnostic(report,"LOADER_MISMATCH"));
    }
    @Test void coremodAndUnknownMetadataReportLimitedCoverage() throws Exception {
        Path file=jar(directory.resolve("unknown.jar"),Map.of("META-INF/coremods.json",bytes("{}")));
        Report report=scan(options(file));assertTrue(diagnostic(report,"TRANSFORMER_NOT_ANALYZED"));assertTrue(diagnostic(report,"LOADER_UNKNOWN"));
    }
}
