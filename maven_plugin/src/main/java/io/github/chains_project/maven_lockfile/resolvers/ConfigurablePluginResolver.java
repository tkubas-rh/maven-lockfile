package io.github.chains_project.maven_lockfile.resolvers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

/**
 * A user-configurable {@link SpecialPluginResolver} that injects a fixed list of dependencies
 * into a named build plugin's recorded dependency set in the lockfile.
 *
 * <p>Unlike the compile-time resolvers registered in {@code LockFileFacade#PLUGIN_RESOLVERS},
 * instances of this class are created by the Plexus Mojo configurator from the lockfile plugin's
 * {@code <configuration>} block in the project's {@code pom.xml}:
 *
 * <pre>{@code
 * <configuration>
 *   <pluginResolvers>
 *     <pluginResolver>
 *       <groupId>com.example</groupId>
 *       <artifactId>my-codegen-plugin</artifactId>
 *       <displayName>my-codegen-plugin (runtime schemas)</displayName>
 *       <dependencies>
 *         <dependency>
 *           <groupId>com.example</groupId>
 *           <artifactId>schema-pack</artifactId>
 *           <version>3.1.0</version>
 *         </dependency>
 *       </dependencies>
 *     </pluginResolver>
 *   </pluginResolvers>
 * </configuration>
 * }</pre>
 *
 * <p>Set {@code <forceDependencyPopulation>true</forceDependencyPopulation>} inside a
 * {@code <pluginResolver>} to resolve each dependency as a standalone root with its full
 * unmediated transitive closure — required for artifacts that run in a separate classloader
 * (e.g. annotation processors).
 */
public class ConfigurablePluginResolver extends SpecialPluginResolver {

    private String groupId;
    private String artifactId;
    private String displayName;
    private List<Dependency> dependencies = Collections.emptyList();
    private boolean forceDependencyPopulation = false;

    /** No-arg constructor required by the Plexus Mojo configurator. */
    public ConfigurablePluginResolver() {}

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies != null ? dependencies : Collections.emptyList();
    }

    public void setForceDependencyPopulation(boolean forceDependencyPopulation) {
        this.forceDependencyPopulation = forceDependencyPopulation;
    }

    @Override
    public boolean isApplicable(MavenProject project) {
        if (groupId == null || artifactId == null) {
            return false;
        }
        return findPlugin(project, groupId, artifactId).isPresent();
    }

    @Override
    public String getDisplayName() {
        return displayName != null ? displayName : groupId + ":" + artifactId;
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        if (dependencies.isEmpty()) {
            return DiscoveryResult.empty();
        }
        String pluginKey = groupId + ":" + artifactId;
        return DiscoveryResult.ofPluginDependencies(pluginKey, new ArrayList<>(dependencies));
    }

    @Override
    public boolean forceDependencyPopulation() {
        return forceDependencyPopulation;
    }
}
