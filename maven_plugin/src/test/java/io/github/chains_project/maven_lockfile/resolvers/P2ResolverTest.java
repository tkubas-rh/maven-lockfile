package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link P2Resolver} — Tycho detection and empty-project resolution.
 */
class P2ResolverTest {

    // -------------------------------------------------------------------------
    // isTychoProject
    // -------------------------------------------------------------------------

    @Test
    void isTychoProject_returnsFalseForProjectWithNoBuildPlugins() {
        MavenProject project = new MavenProject(new Model());

        assertThat(P2Resolver.isTychoProject(project)).isFalse();
    }

    @Test
    void isTychoProject_returnsFalseWhenTychoPluginAbsent() {
        MavenProject project = projectWithPlugin("org.apache.maven.plugins", "maven-compiler-plugin");

        assertThat(P2Resolver.isTychoProject(project)).isFalse();
    }

    @Test
    void isTychoProject_returnsTrueWhenTychoPluginPresent() {
        MavenProject project = projectWithPlugin("org.eclipse.tycho", "tycho-maven-plugin");

        assertThat(P2Resolver.isTychoProject(project)).isTrue();
    }

    @Test
    void isTychoProject_returnsTrueRegardlessOfGroupId() {
        // isTychoProject checks by artifactId only — consistent with other findPlugin usages.
        MavenProject project = projectWithPlugin("com.example", "tycho-maven-plugin");

        assertThat(P2Resolver.isTychoProject(project)).isTrue();
    }

    // -------------------------------------------------------------------------
    // resolve — no .target files present
    // -------------------------------------------------------------------------

    @Test
    void resolve_returnsEmptyResultForProjectWithNoTargetFiles(@TempDir File tempDir) {
        MavenProject project = new MavenProject(new Model());
        project.setFile(new File(tempDir, "pom.xml"));

        P2Resolver.P2ResolverResult result = P2Resolver.resolve(project);

        assertThat(result.getArtifacts()).isEmpty();
        assertThat(result.getRepositories()).isEmpty();
    }

    @Test
    void resolve_returnsEmptyResultForProjectWithNoTargetFilesInSubdir(@TempDir File tempDir) {
        // A subdirectory with no .target files — same result as the parent case.
        File subDir = new File(tempDir, "subproject");
        subDir.mkdirs();
        MavenProject project = new MavenProject(new Model());
        project.setFile(new File(subDir, "pom.xml"));

        P2Resolver.P2ResolverResult result = P2Resolver.resolve(project);

        assertThat(result.getArtifacts()).isEmpty();
        assertThat(result.getRepositories()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MavenProject projectWithPlugin(String groupId, String artifactId) {
        Model model = new Model();
        Build build = new Build();
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion("1.0");
        build.addPlugin(plugin);
        model.setBuild(build);
        return new MavenProject(model);
    }
}
