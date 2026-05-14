package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

/**
 * Discovers the test framework provider that {@code maven-surefire-plugin} loads dynamically at
 * runtime based on which testing library is declared in the project's test dependencies.
 *
 * <p>Surefire selects a provider jar at test-execution time by inspecting the test classpath for
 * known framework marker groupIds, then resolves the matching provider at its own version. None of
 * these provider jars appear in the project's declared dependencies, so they would be missing from
 * the lockfile without this resolver.
 *
 * <p>The mapping from test-framework marker groupId to provider artifactId is defined in
 * {@link #FRAMEWORK_PROVIDER_TABLE}. It mirrors the provider-selection logic inside
 * {@code AbstractSurefireMojo} and covers all providers shipped under
 * {@code org.apache.maven.surefire:surefire-providers}. Adding support for a new provider
 * requires only a new entry in that table — no other code changes.
 */
public class SurefirePluginResolver extends SpecialPluginResolver {

    private static final String SUREFIRE_GROUP_ID = "org.apache.maven.surefire";
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String SUREFIRE_PLUGIN_KEY = "org.apache.maven.plugins:" + SUREFIRE_ARTIFACT_ID;

    /**
     * Maps one or more test-framework marker groupIds to the Surefire provider artifactId that
     * handles them. Entries are evaluated in order; the first match wins.
     *
     * <p>The provider artifactIds here are the module names under
     * {@code org.apache.maven.surefire:surefire-providers}, which can be verified by reading that
     * POM's {@code <modules>} list at the detected Surefire version.
     */
    private static final List<Map.Entry<List<String>, String>> FRAMEWORK_PROVIDER_TABLE = List.of(
            // JUnit 5 — jupiter API or platform API on test classpath
            Map.entry(List.of("org.junit.jupiter", "org.junit.platform"), "surefire-junit-platform"),
            // TestNG
            Map.entry(List.of("org.testng"), "surefire-testng"),
            // JUnit 4 (junit:junit) — use the JUnit 4.7+ provider which supports @RunWith
            Map.entry(List.of("junit"), "surefire-junit47"),
            // JUnit 3 legacy
            Map.entry(List.of("junit-addons"), "surefire-junit3"));

    public SurefirePluginResolver() {}

    @Override
    public boolean isApplicable(MavenProject project) {
        return findPlugin(project, SUREFIRE_ARTIFACT_ID).isPresent() && detectProvider(project) != null;
    }

    @Override
    public String getDisplayName() {
        return "maven-surefire-plugin (provider auto-detection)";
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        String surefireVersion = findPlugin(project, SUREFIRE_ARTIFACT_ID)
                .map(p -> resolveProperty(p.getVersion(), project))
                .orElse(null);

        if (surefireVersion == null || surefireVersion.startsWith("${")) {
            PluginLogManager.getLog().warn("SurefirePluginResolver: could not resolve surefire version — skipping");
            return DiscoveryResult.empty();
        }

        String providerArtifactId = detectProvider(project);
        if (providerArtifactId == null) {
            return DiscoveryResult.empty();
        }

        Dependency provider = createDependency(SUREFIRE_GROUP_ID, providerArtifactId, surefireVersion);

        PluginLogManager.getLog()
                .info(String.format(
                        "SurefirePluginResolver: injecting %s:%s:%s into %s",
                        SUREFIRE_GROUP_ID, providerArtifactId, surefireVersion, SUREFIRE_PLUGIN_KEY));

        return DiscoveryResult.ofPluginDependencies(SUREFIRE_PLUGIN_KEY, List.of(provider));
    }

    /**
     * Scans the project's declared test dependencies and returns the provider artifactId for the
     * first matching entry in {@link #FRAMEWORK_PROVIDER_TABLE}, or {@code null} if no known
     * framework is present.
     *
     * <p>Uses declared dependencies (from the POM model) rather than resolved artifacts so that
     * this check works regardless of the mojo's {@code requiresDependencyResolution} scope —
     * test-scoped artifacts are not in {@code project.getArtifacts()} when the mojo only
     * requests compile-scope resolution.
     */
    private static String detectProvider(MavenProject project) {
        List<String> testGroupIds = project.getDependencies().stream()
                .filter(d -> "test".equals(d.getScope()))
                .map(Dependency::getGroupId)
                .collect(Collectors.toList());

        for (Map.Entry<List<String>, String> entry : FRAMEWORK_PROVIDER_TABLE) {
            if (testGroupIds.stream().anyMatch(entry.getKey()::contains)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
