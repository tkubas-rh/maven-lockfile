package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginConfigResolver} — the data-driven config-reading resolver.
 */
class PluginConfigResolverTest {

    // -------------------------------------------------------------------------
    // isApplicable
    // -------------------------------------------------------------------------

    @Test
    void isNotApplicableWhenTargetPluginAbsent() {
        PluginConfigResolver resolver = PluginConfigResolver.builder("some-plugin")
                .addRule(PluginConfigResolver.gavListToDeps("paths", "path"))
                .build();

        assertThat(resolver.isApplicable(emptyProject())).isFalse();
    }

    @Test
    void isApplicableWhenTargetPluginPresent() {
        PluginConfigResolver resolver = PluginConfigResolver.builder("org.apache.maven.plugins", "maven-compiler-plugin")
                .addRule(PluginConfigResolver.gavListToDeps("annotationProcessorPaths", "path"))
                .build();

        assertThat(resolver.isApplicable(projectWithPlugin("org.apache.maven.plugins", "maven-compiler-plugin", null)))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // gavListToDeps
    // -------------------------------------------------------------------------

    @Test
    void gavListToDepsExtractsAnnotationProcessorPaths() {
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom paths = new Xpp3Dom("annotationProcessorPaths");
        config.addChild(paths);
        Xpp3Dom path = new Xpp3Dom("path");
        paths.addChild(path);
        addChild(path, "groupId", "org.projectlombok");
        addChild(path, "artifactId", "lombok");
        addChild(path, "version", "1.18.30");

        PluginConfigResolver resolver = PluginConfigResolver
                .builder("org.apache.maven.plugins", "maven-compiler-plugin")
                .addRule(PluginConfigResolver.gavListToDeps("annotationProcessorPaths", "path"))
                .build();

        MavenProject project = projectWithPlugin("org.apache.maven.plugins", "maven-compiler-plugin", config);
        var result = resolver.discover(project, null);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.getPluginDependencies())
                .containsKey("org.apache.maven.plugins:maven-compiler-plugin");
        var deps = result.getPluginDependencies().get("org.apache.maven.plugins:maven-compiler-plugin");
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getArtifactId()).isEqualTo("lombok");
        assertThat(deps.get(0).getVersion()).isEqualTo("1.18.30");
    }

    @Test
    void gavListToDepsReturnsEmptyWhenContainerAbsent() {
        PluginConfigResolver resolver = PluginConfigResolver
                .builder("org.apache.maven.plugins", "maven-compiler-plugin")
                .addRule(PluginConfigResolver.gavListToDeps("annotationProcessorPaths", "path"))
                .build();

        // Plugin present but config has no <annotationProcessorPaths>
        MavenProject project = projectWithPlugin(
                "org.apache.maven.plugins", "maven-compiler-plugin", new Xpp3Dom("configuration"));

        assertThat(resolver.discover(project, null).isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // singleValueToSpec
    // -------------------------------------------------------------------------

    @Test
    void singleValueToSpecProducesCorrectSpec() {
        Xpp3Dom config = new Xpp3Dom("configuration");
        addChild(config, "protoc", "3.25.5");

        PluginConfigResolver resolver = PluginConfigResolver
                .builder("io.github.ascopes", "protobuf-maven-plugin")
                .addRule(PluginConfigResolver.singleValueToSpec(
                        "protoc", "com.google.protobuf", "protoc", "exe"))
                .build();

        MavenProject project = projectWithPlugin("io.github.ascopes", "protobuf-maven-plugin", config);
        var result = resolver.discover(project, null);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.getPlatformArtifactSpecs())
                .containsExactly("com.google.protobuf:protoc:exe:3.25.5");
    }

    // -------------------------------------------------------------------------
    // filteredGavListToSpecs
    // -------------------------------------------------------------------------

    @Test
    void filteredGavListToSpecsOnlyPicksMatchingAttribute() {
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom plugins = new Xpp3Dom("plugins");
        config.addChild(plugins);

        Xpp3Dom binaryPlugin = new Xpp3Dom("plugin");
        binaryPlugin.setAttribute("kind", "binary-maven");
        addChild(binaryPlugin, "groupId", "io.grpc");
        addChild(binaryPlugin, "artifactId", "protoc-gen-grpc-java");
        addChild(binaryPlugin, "version", "1.68.0");
        plugins.addChild(binaryPlugin);

        // This one should be ignored (different kind)
        Xpp3Dom otherPlugin = new Xpp3Dom("plugin");
        otherPlugin.setAttribute("kind", "other");
        addChild(otherPlugin, "groupId", "com.example");
        addChild(otherPlugin, "artifactId", "other-plugin");
        addChild(otherPlugin, "version", "1.0.0");
        plugins.addChild(otherPlugin);

        PluginConfigResolver resolver = PluginConfigResolver
                .builder("io.github.ascopes", "protobuf-maven-plugin")
                .addRule(PluginConfigResolver.filteredGavListToSpecs(
                        "plugins", "plugin", "kind", "binary-maven", "exe"))
                .build();

        MavenProject project = projectWithPlugin("io.github.ascopes", "protobuf-maven-plugin", config);
        var result = resolver.discover(project, null);

        assertThat(result.getPlatformArtifactSpecs())
                .containsExactly("io.grpc:protoc-gen-grpc-java:exe:1.68.0");
    }

    // -------------------------------------------------------------------------
    // Multiple rules
    // -------------------------------------------------------------------------

    @Test
    void multipleRulesAreAllApplied() {
        Xpp3Dom config = new Xpp3Dom("configuration");
        addChild(config, "protoc", "3.25.5");

        Xpp3Dom plugins = new Xpp3Dom("plugins");
        config.addChild(plugins);
        Xpp3Dom binaryPlugin = new Xpp3Dom("plugin");
        binaryPlugin.setAttribute("kind", "binary-maven");
        addChild(binaryPlugin, "groupId", "io.grpc");
        addChild(binaryPlugin, "artifactId", "protoc-gen-grpc-java");
        addChild(binaryPlugin, "version", "1.68.0");
        plugins.addChild(binaryPlugin);

        PluginConfigResolver resolver = PluginConfigResolver
                .builder("io.github.ascopes", "protobuf-maven-plugin")
                .addRule(PluginConfigResolver.singleValueToSpec(
                        "protoc", "com.google.protobuf", "protoc", "exe"))
                .addRule(PluginConfigResolver.filteredGavListToSpecs(
                        "plugins", "plugin", "kind", "binary-maven", "exe"))
                .build();

        MavenProject project = projectWithPlugin("io.github.ascopes", "protobuf-maven-plugin", config);
        var result = resolver.discover(project, null);

        assertThat(result.getPlatformArtifactSpecs())
                .containsExactlyInAnyOrder(
                        "com.google.protobuf:protoc:exe:3.25.5",
                        "io.grpc:protoc-gen-grpc-java:exe:1.68.0");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MavenProject emptyProject() {
        return new MavenProject(new Model());
    }

    private static MavenProject projectWithPlugin(String groupId, String artifactId, Xpp3Dom config) {
        Model model = new Model();
        Build build = new Build();
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion("1.0");
        if (config != null) plugin.setConfiguration(config);
        build.addPlugin(plugin);
        model.setBuild(build);
        return new MavenProject(model);
    }

    private static void addChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
