package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlatformArtifactResolver} — OS classifier detection and
 * spec parsing logic that does not require artifact resolution.
 */
class PlatformArtifactResolverTest {

    // -------------------------------------------------------------------------
    // detectOsClassifier
    // -------------------------------------------------------------------------

    @Test
    void detectOsClassifier_readsFromProjectProperty() {
        MavenProject project = projectWithProperty("os.detected.classifier", "linux-x86_64");

        String classifier = PlatformArtifactResolver.detectOsClassifier(project);

        assertThat(classifier).isEqualTo("linux-x86_64");
    }

    @Test
    void detectOsClassifier_trimsWhitespace() {
        MavenProject project = projectWithProperty("os.detected.classifier", "  osx-aarch_64  ");

        String classifier = PlatformArtifactResolver.detectOsClassifier(project);

        assertThat(classifier).isEqualTo("osx-aarch_64");
    }

    @Test
    void detectOsClassifier_fallsBackToSystemPropertiesWhenProjectPropertyAbsent() {
        MavenProject project = new MavenProject(new Model());

        // The current JVM's os.name / os.arch are real, so the result should be non-null
        // on any CI platform that runs this test (Linux/Mac/Windows x86_64 or aarch64).
        String classifier = PlatformArtifactResolver.detectOsClassifier(project);

        // We can't assert the exact value (depends on CI platform),
        // but it must follow the pattern <os>-<arch> and not be blank.
        if (classifier != null) {
            assertThat(classifier).matches("[a-z]+[-_][a-z0-9_]+");
        }
        // null is acceptable when the JVM platform is not in the recognized list.
    }

    // -------------------------------------------------------------------------
    // resolve — spec parsing (no checksumCalculator needed for these paths)
    // -------------------------------------------------------------------------

    @Test
    void resolve_returnsEmptyListForEmptySpecs() {
        // Empty spec list → no checksumCalculator calls, empty result
        List<String> specs = List.of();

        var result = PlatformArtifactResolver.resolve(specs, "linux-x86_64", null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_skipsMalformedSpecWithTooFewParts() {
        // Spec with only 3 parts (groupId:artifactId:version) — missing type
        List<String> specs = List.of("com.google.protobuf:protoc:3.25.5");

        // Must not throw even though checksumCalculator is null (malformed → skipped before use)
        var result = PlatformArtifactResolver.resolve(specs, "linux-x86_64", null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_skipsMalformedSpecWithTooManyParts() {
        // Spec with 5 parts — extra segment
        List<String> specs = List.of("com.google.protobuf:protoc:exe:3.25.5:extra");

        var result = PlatformArtifactResolver.resolve(specs, "linux-x86_64", null);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MavenProject projectWithProperty(String key, String value) {
        Model model = new Model();
        model.addProperty(key, value);
        return new MavenProject(model);
    }
}
