package io.github.chains_project.maven_lockfile.resolvers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Base class for resolvers that discover artifacts dynamically loaded by specific Maven build
 * plugins — artifacts that are not declared in the project's {@code pom.xml} but are required
 * for a hermetic offline build.
 *
 * <p>Implement this class to add support for a new plugin. Two categories of artifacts are
 * supported via {@link DiscoveryResult}:
 * <ul>
 *   <li><b>Plugin dependencies</b> — injected into a specific plugin's dependency resolution
 *       (e.g. Quarkus deployment artifacts injected into {@code quarkus-maven-plugin}).</li>
 *   <li><b>Platform artifact specs</b> — native OS binaries resolved for the current platform
 *       (e.g. {@code protoc} resolved by {@code protobuf-maven-plugin}).</li>
 * </ul>
 *
 * <p>Register new implementations in
 * {@code LockFileFacade#PLUGIN_RESOLVERS}.
 */
public abstract class SpecialPluginResolver {

    /**
     * Returns {@code true} if this resolver applies to the given project (i.e. the plugin it
     * handles is present in the project's build plugins).
     */
    public abstract boolean isApplicable(MavenProject project);

    /**
     * A short human-readable name for log messages (e.g. {@code "Quarkus"}).
     */
    public abstract String getDisplayName();

    /**
     * Discovers artifacts that the plugin loads dynamically and returns them as a
     * {@link DiscoveryResult}.
     *
     * @param project the Maven project
     * @param session the Maven session
     * @return discovery result (may be {@link DiscoveryResult#empty()})
     */
    public abstract DiscoveryResult discover(MavenProject project, MavenSession session);

    /**
     * Returns {@code true} if conflict-loser nodes — including duplicates
     * ({@code selectedVersion == version}) — should have their children populated during
     * lockfile generation for this plugin's dependency graph.
     *
     * <p>Override to {@code true} for plugins whose dependencies are loaded in a separate
     * classloader (e.g. annotation processors via {@code maven-compiler-plugin}), where
     * Maven's main dependency mediation does not apply and every resolved artifact must
     * be available in the local repository regardless of the project-level conflict winner.
     */
    public boolean forceDependencyPopulation() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Shared utilities for subclasses
    // -------------------------------------------------------------------------

    /**
     * Returns the first build plugin matching the given {@code artifactId}, or empty.
     * Use this when the groupId is well-known enough to be omitted (e.g. org.apache.maven.plugins
     * are always on the default group prefix list and uniquely identified by artifactId alone).
     */
    protected static Optional<Plugin> findPlugin(MavenProject project, String artifactId) {
        return project.getBuildPlugins().stream()
                .filter(p -> artifactId.equals(p.getArtifactId()))
                .findFirst();
    }

    /**
     * Returns the first build plugin matching both {@code groupId} and {@code artifactId},
     * or empty. Use this when multiple plugins from different groups share the same artifactId.
     */
    protected static Optional<Plugin> findPlugin(MavenProject project, String groupId, String artifactId) {
        return project.getBuildPlugins().stream()
                .filter(p -> groupId.equals(p.getGroupId()) && artifactId.equals(p.getArtifactId()))
                .findFirst();
    }

    /**
     * Creates a {@link Dependency} with the given GAV coordinates.
     */
    protected static Dependency createDependency(String groupId, String artifactId, String version) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        return dep;
    }

    /**
     * Resolves a Maven property placeholder ({@code ${property.name}}) against the project's
     * effective properties. Returns the value unchanged if it is not a placeholder or the
     * property cannot be found.
     */
    protected static String resolveProperty(String value, MavenProject project) {
        if (value == null) return null;
        value = value.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            String resolved = project.getProperties().getProperty(key);
            if (resolved != null) return resolved.trim();
        }
        return value;
    }

    /**
     * Reads a child element's text value from an {@link Xpp3Dom} node and resolves any
     * {@code ${property}} placeholder. Returns {@code null} if the child is absent or has
     * no value.
     */
    protected static String childValue(Xpp3Dom parent, String childName, MavenProject project) {
        Xpp3Dom child = parent.getChild(childName);
        if (child == null || child.getValue() == null) return null;
        return resolveProperty(child.getValue(), project);
    }

    /**
     * Holds the artifacts discovered by a {@link SpecialPluginResolver}, split into two
     * categories that are applied differently in {@code LockFileFacade}:
     *
     * <ul>
     *   <li>{@link #pluginDependencies} — mapped by plugin key ({@code groupId:artifactId});
     *       injected as user-declared dependencies for the target plugin's resolution.</li>
     *   <li>{@link #platformArtifactSpecs} — {@code groupId:artifactId:type:version} strings
     *       for native OS binaries; resolved for the current platform classifier.</li>
     * </ul>
     */
    public static final class DiscoveryResult {

        private final Map<String, List<Dependency>> pluginDependencies;
        private final List<String> platformArtifactSpecs;

        private DiscoveryResult(Map<String, List<Dependency>> pluginDependencies, List<String> platformArtifactSpecs) {
            this.pluginDependencies = pluginDependencies;
            this.platformArtifactSpecs = platformArtifactSpecs;
        }

        /** Result with no discovered artifacts. */
        public static DiscoveryResult empty() {
            return new DiscoveryResult(Collections.emptyMap(), Collections.emptyList());
        }

        /**
         * Result carrying dependencies to inject into a specific plugin's resolution.
         *
         * @param pluginKey  {@code groupId:artifactId} of the target plugin
         * @param deps       dependencies to inject
         */
        public static DiscoveryResult ofPluginDependencies(String pluginKey, List<Dependency> deps) {
            return new DiscoveryResult(Map.of(pluginKey, deps), Collections.emptyList());
        }

        /**
         * Result carrying platform artifact specs for native OS binary resolution.
         *
         * @param specs {@code groupId:artifactId:type:version} strings
         */
        public static DiscoveryResult ofPlatformArtifacts(List<String> specs) {
            return new DiscoveryResult(Collections.emptyMap(), specs);
        }

        /**
         * Returns dependencies to inject, keyed by plugin {@code groupId:artifactId}.
         */
        public Map<String, List<Dependency>> getPluginDependencies() {
            return pluginDependencies;
        }

        /**
         * Returns platform artifact specs ({@code groupId:artifactId:type:version}).
         */
        public List<String> getPlatformArtifactSpecs() {
            return platformArtifactSpecs;
        }

        public boolean isEmpty() {
            return pluginDependencies.isEmpty() && platformArtifactSpecs.isEmpty();
        }
    }
}
