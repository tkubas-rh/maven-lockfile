package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SurefirePluginResolver#isApplicable} and
 * {@link SurefirePluginResolver#forceDependencyPopulation}.
 */
class SurefirePluginResolverTest {

    private SurefirePluginResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SurefirePluginResolver();
    }

    @Test
    void isNotApplicableWhenSurefirePluginAbsent() {
        // A project with no build plugins at all — resolver must not trigger
        MavenProject project = new MavenProject(new Model());

        assertThat(resolver.isApplicable(project)).isFalse();
    }

    @Test
    void isNotApplicableWhenSurefirePresentButNoJUnit5() {
        // Surefire plugin present, but no JUnit 5 declared — resolver must stay quiet
        Model model = new Model();
        Build build = new Build();
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.2.5");
        build.addPlugin(surefire);
        model.setBuild(build);

        MavenProject project = new MavenProject(model);

        assertThat(resolver.isApplicable(project)).isFalse();
    }

    @Test
    void isApplicableWhenSurefireAndJUnit5Present() {
        // Both surefire plugin and JUnit Jupiter declared as test dep — resolver must trigger
        Model model = new Model();
        Build build = new Build();
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.2.5");
        build.addPlugin(surefire);
        model.setBuild(build);

        Dependency junitJupiter = new Dependency();
        junitJupiter.setGroupId("org.junit.jupiter");
        junitJupiter.setArtifactId("junit-jupiter");
        junitJupiter.setVersion("5.10.2");
        junitJupiter.setScope("test");
        model.addDependency(junitJupiter);

        MavenProject project = new MavenProject(model);

        assertThat(resolver.isApplicable(project)).isTrue();
    }

    @Test
    void isApplicableWhenSurefireAndTestNGPresent() {
        // TestNG as test dep should trigger the testng provider row in the table
        Model model = new Model();
        Build build = new Build();
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.2.5");
        build.addPlugin(surefire);
        model.setBuild(build);

        Dependency testng = new Dependency();
        testng.setGroupId("org.testng");
        testng.setArtifactId("testng");
        testng.setVersion("7.9.0");
        testng.setScope("test");
        model.addDependency(testng);

        MavenProject project = new MavenProject(model);

        assertThat(resolver.isApplicable(project)).isTrue();
    }

    @Test
    void isNotApplicableWhenSurefirePresentButNoKnownFramework() {
        // Surefire present but only a non-test-framework dep — resolver must not trigger
        Model model = new Model();
        Build build = new Build();
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.2.5");
        build.addPlugin(surefire);
        model.setBuild(build);

        Dependency unrelated = new Dependency();
        unrelated.setGroupId("com.google.guava");
        unrelated.setArtifactId("guava");
        unrelated.setVersion("33.0.0-jre");
        unrelated.setScope("compile");
        model.addDependency(unrelated);

        MavenProject project = new MavenProject(model);

        assertThat(resolver.isApplicable(project)).isFalse();
    }

    @Test
    void forceDependencyPopulationIsFalse() {
        // Surefire-junit-platform is injected as a plugin dependency, not a standalone root
        assertThat(resolver.forceDependencyPopulation()).isFalse();
    }

    @Test
    void displayNameIsHumanReadable() {
        assertThat(resolver.getDisplayName()).contains("surefire");
    }
}
