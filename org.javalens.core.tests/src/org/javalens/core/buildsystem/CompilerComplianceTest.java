package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.LoadedFixture;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug G — Maven compiler source level not applied to {@code IJavaProject}.
 *
 * <p>Without this fix, {@code JavaCore.COMPILER_SOURCE} / {@code COMPILER_COMPLIANCE} fall
 * back to JDT defaults regardless of what the project declares. Source code that uses
 * Java 21 features (for example record patterns from JEP 440) gets parsed against an older
 * grammar and reports spurious syntax errors.
 *
 * <p>The fix reads {@code maven.compiler.release} > {@code maven.compiler.source} >
 * {@code maven.compiler.target} from {@code pom.xml} and calls {@code setOption} on the
 * configured {@link org.eclipse.jdt.core.IJavaProject}.
 */
class CompilerComplianceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven source level from pom is applied to IJavaProject options")
    void mavenSourceLevelAppliedFromPom() throws Exception {
        // The fixture deliberately declares 17 (different from the workspace/runtime default
        // of 21) so this assertion can only pass when the per-project option was actually
        // read from pom and set on the IJavaProject.
        LoadedFixture fixture = helper.loadFixture("compliance-level-mismatch");

        assertEquals("17", fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to come from pom <maven.compiler.source>");
        assertEquals("17", fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to come from pom <maven.compiler.source>");
    }

    @Test
    @DisplayName("project with declared compiler level produces no COMPLIANCE_LEVEL_UNKNOWN warning")
    void declaredLevelProducesNoComplianceWarning() throws Exception {
        LoadedFixture fixture = helper.loadFixture("compliance-level-mismatch");

        List<LoadWarning> warnings = fixture.service().getWarnings();
        boolean hasComplianceWarning = warnings.stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(!hasComplianceWarning,
            "Did not expect COMPLIANCE_LEVEL_UNKNOWN when pom declares maven.compiler.source. " +
            "Warnings: " + warnings);
    }

    @Test
    @DisplayName("plain Java project falls back to runtime JVM major version")
    void plainJavaFallsBackToRuntimeVersion() throws Exception {
        // No pom.xml / build.gradle / BUILD.bazel — applyCompilerOptions's fallback uses
        // Runtime.version().feature() rather than silently inheriting a JDT default.
        LoadedFixture fixture = helper.loadFixture("plain-java");

        String expected = String.valueOf(Runtime.version().feature());
        assertEquals(expected, fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to default to the runtime JVM major version when " +
            "no build file declares a compliance level");
        assertEquals(expected, fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to default to the runtime JVM major version");

        // Plain Java has no place to declare a level, so the fallback is expected — no
        // warning. (For Maven/Gradle/Bazel projects without a declared level,
        // COMPLIANCE_LEVEL_UNKNOWN fires; that path is covered by the
        // unknownLevelOnDetectedBuildSystemEmitsWarning case below.)
        boolean hasComplianceWarning = fixture.service().getWarnings().stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(!hasComplianceWarning,
            "COMPLIANCE_LEVEL_UNKNOWN should not fire on plain-Java projects. " +
            "Warnings: " + fixture.service().getWarnings());
    }

    @Test
    @DisplayName("Maven plugin <configuration><release> is read in addition to pom <properties>")
    void mavenComplianceFromPluginConfigurationRelease() throws Exception {
        LoadedFixture fixture = helper.loadFixture("compliance-from-plugin-config");

        assertEquals("17", fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to come from <plugin>maven-compiler-plugin</plugin>" +
            "<configuration><release>17</release></configuration>");
        assertEquals("17", fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to come from the plugin <configuration> block");
    }

    @Test
    @DisplayName("COMPLIANCE_LEVEL_UNKNOWN fires when a build system declares no compiler level")
    void unknownLevelOnDetectedBuildSystemEmitsWarning() throws Exception {
        // Maven project with no maven.compiler.* property AND no maven-compiler-plugin
        // <configuration>. Detection identifies it as Maven, the level extractor returns
        // null, and we fall back to Runtime.version().feature() — but emit
        // COMPLIANCE_LEVEL_UNKNOWN so the agent knows analysis used the runtime default.
        LoadedFixture fixture = helper.loadFixture("maven-no-compliance-declared");

        boolean hasComplianceWarning = fixture.service().getWarnings().stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(hasComplianceWarning,
            "Expected COMPLIANCE_LEVEL_UNKNOWN when a Maven project declares no compiler " +
            "level. Warnings: " + fixture.service().getWarnings());

        // The fallback level itself should equal the runtime feature version so analysis
        // can still parse modern syntax.
        String expected = String.valueOf(Runtime.version().feature());
        assertEquals(expected, fixture.classpath().compilerSource(),
            "Expected runtime fallback when no compiler level is declared");
    }

    @Test
    @DisplayName("Properties precedence: maven.compiler.release wins over maven.compiler.source")
    void propertiesPrecedence_releaseWinsOverSource() throws Exception {
        // detectCompilerLevel iterates ["release", "source", "target"]; the first non-null
        // wins. Mutate compliance-level-mismatch (declares source=17) to also declare
        // release=11. Expected outcome: release wins → "11", not "17".
        Path projectRoot = helper.copyFixture("compliance-level-mismatch");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        String withRelease = original.replace(
            "<maven.compiler.source>17</maven.compiler.source>",
            "<maven.compiler.release>11</maven.compiler.release>\n"
                + "        <maven.compiler.source>17</maven.compiler.source>");
        Files.writeString(pom, withRelease);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals("11", snapshot.compilerSource(),
            "maven.compiler.release must win over maven.compiler.source per documented "
                + "precedence; got: " + snapshot.compilerSource());
    }

    @Test
    @DisplayName("Plugin precedence: <release> wins over <source> within <configuration>")
    void pluginPrecedence_releaseWinsOverSource() throws Exception {
        // detectCompilerLevel's plugin branch iterates ["release", "source", "target"].
        // Mutate compliance-from-plugin-config (declares <release>17</release>) to also
        // include <source>11</source>. Expected outcome: release still wins → "17".
        Path projectRoot = helper.copyFixture("compliance-from-plugin-config");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        String withSource = original.replace(
            "<release>17</release>",
            "<release>17</release>\n                    <source>11</source>");
        Files.writeString(pom, withSource);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals("17", snapshot.compilerSource(),
            "Plugin <release> must win over <source> per documented precedence; got: "
                + snapshot.compilerSource());
    }

    @Test
    @DisplayName("Cross-section precedence: pom <properties> win over plugin <configuration>")
    void propertiesWinOverPluginConfiguration() throws Exception {
        // detectCompilerLevel tries <properties> before the plugin block. Construct a pom
        // declaring BOTH: properties=11, plugin <release>=17. Properties must win → "11".
        Path projectRoot = helper.copyFixture("compliance-from-plugin-config");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        // Insert a <properties> block declaring maven.compiler.source=11 inside <project>.
        // (compliance-from-plugin-config has no existing <properties> for compiler.)
        String withProperties = original.replace(
            "</modelVersion>",
            "</modelVersion>\n\n    <properties>\n"
                + "        <maven.compiler.source>11</maven.compiler.source>\n"
                + "    </properties>");
        Files.writeString(pom, withProperties);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals("11", snapshot.compilerSource(),
            "pom <properties> must win over plugin <configuration> per documented "
                + "precedence; got: " + snapshot.compilerSource());
    }

    @Test
    @DisplayName("applyCompilerOptions pins COMPILER_PB_UNUSED_IMPORT=WARNING and CODEGEN_TARGET_PLATFORM=source")
    void applyCompilerOptionsPinsAuxiliaryOptions() throws Exception {
        // Two non-source/compliance options that applyCompilerOptions sets explicitly. The
        // unused-import option is load-bearing for the get_quick_fixes tool — its
        // "UnusedImport → remove_import" documented fix path depends on JDT surfacing the
        // problem as an IProblem, which only happens when the option is WARNING/ERROR.
        // ClasspathSnapshot doesn't (and shouldn't) expose these; read directly.
        LoadedFixture fixture = helper.loadFixture("compliance-level-mismatch");

        String unusedImport = fixture.service().getJavaProject()
            .getOption(JavaCore.COMPILER_PB_UNUSED_IMPORT, true);
        assertEquals(JavaCore.WARNING, unusedImport,
            "COMPILER_PB_UNUSED_IMPORT must be WARNING so JDT reconcile surfaces the "
                + "UnusedImport problem (get_quick_fixes UnusedImport → remove_import path); "
                + "got: " + unusedImport);

        String codegenTarget = fixture.service().getJavaProject()
            .getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true);
        assertEquals("17", codegenTarget,
            "COMPILER_CODEGEN_TARGET_PLATFORM must match the declared source level so "
                + "bytecode targets the same JVM family; got: " + codegenTarget);
    }

    @Test
    @DisplayName("Source level higher than JDT supports falls back to a known recent level — no crash")
    void higherThanSupportedSourceLevel_fallsBackGracefully() throws Exception {
        // The compliance-level extractor reads the raw string from pom. If a project declares
        // a future Java version the bundled JDT can't compile against (e.g. "99"), the load
        // must not crash; JDT either rejects the option silently and keeps a known default,
        // or surfaces COMPLIANCE_LEVEL_UNKNOWN. The contract is: load succeeds, the resulting
        // compilerSource is a non-null parseable Java level, and analysis remains usable.
        Path projectRoot = helper.copyFixture("compliance-level-mismatch");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        String bumped = original.replace(
            "<maven.compiler.source>17</maven.compiler.source>",
            "<maven.compiler.source>99</maven.compiler.source>")
            .replace(
                "<maven.compiler.target>17</maven.compiler.target>",
                "<maven.compiler.target>99</maven.compiler.target>")
            .replace(
                "<maven.compiler.release>17</maven.compiler.release>",
                "<maven.compiler.release>99</maven.compiler.release>");
        Files.writeString(pom, bumped);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        String source = snapshot.compilerSource();
        assertTrue(source != null && !source.isBlank(),
            "Even with an unsupported source level declared, compilerSource must be set; got: "
                + source);
        // The level must parse as a positive integer — that's the contract on the surface.
        // JDT may accept "99" verbatim (forward-tolerance) or substitute a known-good fallback;
        // either is fine, just not an empty/null value or a non-numeric token.
        int parsed = Integer.parseInt(source);
        assertTrue(parsed >= 1,
            "compilerSource must be a positive Java level; got: " + source);
    }

    @Test
    @DisplayName("Maven plugin <configuration> works with artifactId-before-groupId ordering")
    void mavenComplianceFromPluginWithReversedIdentityOrder() throws Exception {
        // Maven's POM schema doesn't require groupId-before-artifactId. The regex must
        // accept either order. Mutate the copied fixture so artifactId comes first, then
        // load and assert compliance still resolves.
        Path projectRoot = helper.copyFixture("compliance-from-plugin-config");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        String reversed = original.replace(
            "<groupId>org.apache.maven.plugins</groupId>\n" +
                "                <artifactId>maven-compiler-plugin</artifactId>",
            "<artifactId>maven-compiler-plugin</artifactId>\n" +
                "                <groupId>org.apache.maven.plugins</groupId>");
        Files.writeString(pom, reversed);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals("17", snapshot.compilerSource(),
            "Expected COMPILER_SOURCE to resolve regardless of groupId/artifactId ordering");
        assertEquals("17", snapshot.compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to resolve regardless of groupId/artifactId ordering");
    }
}
