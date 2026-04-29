package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpecialPluginResolver.DiscoveryResult} factory methods and
 * {@link SpecialPluginResolver#forceDependencyPopulation()} default contract.
 */
class SpecialPluginResolverTest {

    @Test
    void emptyResultIsEmpty() {
        var result = SpecialPluginResolver.DiscoveryResult.empty();

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getPluginDependencies()).isEmpty();
        assertThat(result.getPlatformArtifactSpecs()).isEmpty();
    }

    @Test
    void pluginDependenciesResultIsNotEmpty() {
        Dependency dep = new Dependency();
        dep.setGroupId("org.example");
        dep.setArtifactId("example-lib");
        dep.setVersion("1.0.0");

        var result = SpecialPluginResolver.DiscoveryResult.ofPluginDependencies(
                "org.example:example-plugin", List.of(dep));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.getPlatformArtifactSpecs()).isEmpty();
        assertThat(result.getPluginDependencies())
                .containsKey("org.example:example-plugin");
        assertThat(result.getPluginDependencies().get("org.example:example-plugin"))
                .hasSize(1)
                .first()
                .satisfies(d -> {
                    assertThat(d.getGroupId()).isEqualTo("org.example");
                    assertThat(d.getArtifactId()).isEqualTo("example-lib");
                    assertThat(d.getVersion()).isEqualTo("1.0.0");
                });
    }

    @Test
    void platformArtifactsResultIsNotEmpty() {
        var specs = List.of(
                "com.google.protobuf:protoc:exe:3.25.5",
                "io.grpc:protoc-gen-grpc-java:exe:1.68.0");

        var result = SpecialPluginResolver.DiscoveryResult.ofPlatformArtifacts(specs);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.getPluginDependencies()).isEmpty();
        assertThat(result.getPlatformArtifactSpecs())
                .hasSize(2)
                .containsExactlyInAnyOrderElementsOf(specs);
    }

    @Test
    void forceDependencyPopulationDefaultIsFalse() {
        // Verify the base-class default — subclasses opt-in by overriding
        SpecialPluginResolver resolver = new SpecialPluginResolver() {
            @Override
            public boolean isApplicable(org.apache.maven.project.MavenProject project) {
                return false;
            }

            @Override
            public String getDisplayName() {
                return "test";
            }

            @Override
            public DiscoveryResult discover(
                    org.apache.maven.project.MavenProject project,
                    org.apache.maven.execution.MavenSession session) {
                return DiscoveryResult.empty();
            }
        };

        assertThat(resolver.forceDependencyPopulation()).isFalse();
    }
}
