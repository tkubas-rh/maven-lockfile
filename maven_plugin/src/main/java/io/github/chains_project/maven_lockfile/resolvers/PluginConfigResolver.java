package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * A data-driven {@link SpecialPluginResolver} that discovers additional artifacts by reading a
 * Maven build plugin's {@code <configuration>} XML. Each instance is built from a list of
 * {@link Rule} objects that describe where to look in the XML and what to extract.
 *
 * <p>This eliminates the need for a dedicated Java class for every plugin that exposes its
 * implicit artifact dependencies through its configuration XML. Adding support for a new plugin
 * requires only a new entry in {@code LockFileFacade#PLUGIN_RESOLVERS} — no new source file:
 *
 * <pre>{@code
 * PluginConfigResolver.builder("com.example", "my-code-generator-plugin")
 *     .displayName("my-code-generator-plugin (schemas)")
 *     .addRule(PluginConfigResolver.gavListToDeps("schemas", "schema"))
 *     .build()
 * }</pre>
 *
 * <h3>Available rule factories</h3>
 * <ul>
 *   <li>{@link #gavListToDeps} — reads repeating elements with GAV children → plugin deps</li>
 *   <li>{@link #singleValueToSpec} — reads a single element containing a version → platform spec</li>
 *   <li>{@link #filteredGavListToSpecs} — reads attribute-filtered elements with GAV → platform specs</li>
 * </ul>
 */
public final class PluginConfigResolver extends SpecialPluginResolver {

    /**
     * A single extraction instruction: reads from a plugin's {@code <configuration>} DOM
     * and appends discovered coordinates to the dependency list or the platform spec list.
     */
    @FunctionalInterface
    public interface Rule {
        void apply(Xpp3Dom config, MavenProject project,
                   List<Dependency> depsOut, List<String> specsOut);
    }

    private final String pluginGroupId;   // null = match by artifactId only
    private final String pluginArtifactId;
    private final String pluginKey;
    private final String displayName;
    private final boolean force;
    private final List<Rule> rules;

    private PluginConfigResolver(
            String pluginGroupId,
            String pluginArtifactId,
            String displayName,
            boolean force,
            List<Rule> rules) {
        this.pluginGroupId = pluginGroupId;
        this.pluginArtifactId = pluginArtifactId;
        this.pluginKey = (pluginGroupId != null ? pluginGroupId : "org.apache.maven.plugins")
                + ":" + pluginArtifactId;
        this.displayName = displayName;
        this.force = force;
        this.rules = List.copyOf(rules);
    }

    @Override
    public boolean isApplicable(MavenProject project) {
        return pluginGroupId != null
                ? findPlugin(project, pluginGroupId, pluginArtifactId).isPresent()
                : findPlugin(project, pluginArtifactId).isPresent();
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean forceDependencyPopulation() {
        return force;
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        Plugin plugin = pluginGroupId != null
                ? findPlugin(project, pluginGroupId, pluginArtifactId).orElse(null)
                : findPlugin(project, pluginArtifactId).orElse(null);

        if (plugin == null || !(plugin.getConfiguration() instanceof Xpp3Dom)) {
            return DiscoveryResult.empty();
        }

        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        List<Dependency> deps = new ArrayList<>();
        List<String> specs = new ArrayList<>();

        for (Rule rule : rules) {
            rule.apply(config, project, deps, specs);
        }

        if (!deps.isEmpty()) {
            PluginLogManager.getLog().info(String.format(
                    "%s: injecting %d artifact(s) into %s", displayName, deps.size(), pluginKey));
            return DiscoveryResult.ofPluginDependencies(pluginKey, deps);
        }
        if (!specs.isEmpty()) {
            PluginLogManager.getLog().info(String.format(
                    "%s: discovered %d platform artifact spec(s)", displayName, specs.size()));
            return DiscoveryResult.ofPlatformArtifacts(specs);
        }
        return DiscoveryResult.empty();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Starts building a resolver for a plugin identified by both groupId and artifactId. */
    public static Builder builder(String groupId, String artifactId) {
        return new Builder(groupId, artifactId);
    }

    /**
     * Starts building a resolver for a well-known plugin identified by artifactId alone
     * (safe when the artifactId is unique across all groups in practice).
     */
    public static Builder builder(String artifactId) {
        return new Builder(null, artifactId);
    }

    public static final class Builder {
        private final String groupId;
        private final String artifactId;
        private String displayName;
        private boolean force;
        private final List<Rule> rules = new ArrayList<>();

        private Builder(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.displayName = artifactId + " (config-driven)";
        }

        public Builder displayName(String name) {
            this.displayName = name;
            return this;
        }

        /**
         * Annotation processors and similar plugin-classloader artifacts must all be present
         * offline even when Maven's main resolver would deduplicate them. Call this when the
         * discovered deps run in a separate classloader from the project dependency graph.
         */
        public Builder forceDependencyPopulation() {
            this.force = true;
            return this;
        }

        public Builder addRule(Rule rule) {
            this.rules.add(rule);
            return this;
        }

        public PluginConfigResolver build() {
            return new PluginConfigResolver(groupId, artifactId, displayName, force, rules);
        }
    }

    // -------------------------------------------------------------------------
    // Rule factories
    // -------------------------------------------------------------------------

    /**
     * Reads a list of repeating XML elements, each containing {@code <groupId>},
     * {@code <artifactId>}, and {@code <version>} children, and adds them as plugin
     * {@link Dependency} objects.
     *
     * <p>Example — each {@code <path>} under {@code <annotationProcessorPaths>}:
     * <pre>{@code
     * gavListToDeps("annotationProcessorPaths", "path")
     * }</pre>
     *
     * Matches this config:
     * <pre>{@code
     * <annotationProcessorPaths>
     *   <path>
     *     <groupId>org.projectlombok</groupId>
     *     <artifactId>lombok</artifactId>
     *     <version>1.18.30</version>
     *   </path>
     * </annotationProcessorPaths>
     * }</pre>
     */
    public static Rule gavListToDeps(String containerElement, String itemElement) {
        return (config, project, deps, specs) -> {
            Xpp3Dom container = config.getChild(containerElement);
            if (container == null) return;
            for (Xpp3Dom item : container.getChildren(itemElement)) {
                String groupId   = childValue(item, "groupId",    project);
                String artifactId = childValue(item, "artifactId", project);
                String version   = childValue(item, "version",    project);
                if (groupId == null || artifactId == null || version == null) {
                    PluginLogManager.getLog().warn(String.format(
                            "PluginConfigResolver: skipping <%s> with missing GAV"
                                    + " (groupId=%s artifactId=%s version=%s)",
                            itemElement, groupId, artifactId, version));
                    continue;
                }
                if (version.startsWith("${")) {
                    PluginLogManager.getLog().warn(String.format(
                            "PluginConfigResolver: unresolved version %s for %s:%s — skipping",
                            version, groupId, artifactId));
                    continue;
                }
                Dependency dep = new Dependency();
                dep.setGroupId(groupId);
                dep.setArtifactId(artifactId);
                dep.setVersion(version);
                deps.add(dep);
            }
        };
    }

    /**
     * Reads a single element whose text content is a version string and produces a platform
     * artifact spec with a fixed groupId, artifactId, and type.
     *
     * <p>Example — {@code <protoc>3.25.5</protoc>} → {@code com.google.protobuf:protoc:exe:3.25.5}:
     * <pre>{@code
     * singleValueToSpec("protoc", "com.google.protobuf", "protoc", "exe")
     * }</pre>
     *
     * Matches this config:
     * <pre>{@code
     * <configuration>
     *   <protoc>3.25.5</protoc>
     * </configuration>
     * }</pre>
     */
    public static Rule singleValueToSpec(
            String element, String fixedGroupId, String fixedArtifactId, String type) {
        return (config, project, deps, specs) -> {
            Xpp3Dom elem = config.getChild(element);
            if (elem == null || elem.getValue() == null) return;
            String version = resolveProperty(elem.getValue(), project);
            if (version != null && !version.isBlank() && !version.startsWith("${")) {
                specs.add(fixedGroupId + ":" + fixedArtifactId + ":" + type + ":" + version);
            }
        };
    }

    /**
     * Reads repeating elements that match a required attribute value, each containing
     * {@code <groupId>}, {@code <artifactId>}, and {@code <version>} children, and produces
     * platform artifact specs.
     *
     * <p>Example — each {@code <plugin kind="binary-maven">} under {@code <plugins>}
     * → {@code groupId:artifactId:exe:version}:
     * <pre>{@code
     * filteredGavListToSpecs("plugins", "plugin", "kind", "binary-maven", "exe")
     * }</pre>
     *
     * Matches this config:
     * <pre>{@code
     * <plugins>
     *   <plugin kind="binary-maven">
     *     <groupId>io.grpc</groupId>
     *     <artifactId>protoc-gen-grpc-java</artifactId>
     *     <version>1.68.0</version>
     *   </plugin>
     * </plugins>
     * }</pre>
     */
    public static Rule filteredGavListToSpecs(
            String containerElement, String itemElement,
            String attrName, String attrValue,
            String type) {
        return (config, project, deps, specs) -> {
            Xpp3Dom container = config.getChild(containerElement);
            if (container == null) return;
            for (Xpp3Dom item : container.getChildren(itemElement)) {
                if (!attrValue.equals(item.getAttribute(attrName))) continue;
                String groupId    = childValue(item, "groupId",    project);
                String artifactId = childValue(item, "artifactId", project);
                String version    = childValue(item, "version",    project);
                if (groupId == null || artifactId == null || version == null) {
                    PluginLogManager.getLog().warn(String.format(
                            "PluginConfigResolver: skipping <%s %s=%s> with missing GAV",
                            itemElement, attrName, attrValue));
                    continue;
                }
                specs.add(groupId + ":" + artifactId + ":" + type + ":" + version);
            }
        };
    }
}
