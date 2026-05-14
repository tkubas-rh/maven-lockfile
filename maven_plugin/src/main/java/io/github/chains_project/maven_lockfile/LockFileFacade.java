package io.github.chains_project.maven_lockfile;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.checksum.RepositoryInformation;
import io.github.chains_project.maven_lockfile.data.*;
import io.github.chains_project.maven_lockfile.data.P2DependencyNode;
import io.github.chains_project.maven_lockfile.data.P2Repository;
import io.github.chains_project.maven_lockfile.graph.DependencyGraph;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import io.github.chains_project.maven_lockfile.resolvers.BomResolver;
import io.github.chains_project.maven_lockfile.resolvers.ExtraArtifactResolver;
import io.github.chains_project.maven_lockfile.resolvers.P2Resolver;
import io.github.chains_project.maven_lockfile.resolvers.PlatformArtifactResolver;
import io.github.chains_project.maven_lockfile.resolvers.PluginConfigResolver;
import io.github.chains_project.maven_lockfile.resolvers.ProjectBuilder;
import io.github.chains_project.maven_lockfile.resolvers.QuarkusDeploymentResolver;
import io.github.chains_project.maven_lockfile.resolvers.SpecialPluginResolver;
import io.github.chains_project.maven_lockfile.resolvers.SurefirePluginResolver;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.util.artifact.JavaScopes;

/**
 * Entry point for the lock file generation. This class is responsible for generating the lock file for a project.
 *
 */
public class LockFileFacade {

    /**
     * Registry of all special plugin resolvers. Add new implementations here to extend
     * lockfile generation to new build plugins without touching any other code.
     */
    private static final List<SpecialPluginResolver> PLUGIN_RESOLVERS = List.of(
            new QuarkusDeploymentResolver(),
            new SurefirePluginResolver(),
            PluginConfigResolver.builder("org.apache.maven.plugins", "maven-compiler-plugin")
                    .displayName("maven-compiler-plugin (annotationProcessorPaths)")
                    .forceDependencyPopulation()
                    .addRule(PluginConfigResolver.gavListToDeps("annotationProcessorPaths", "path"))
                    .build(),
            PluginConfigResolver.builder("io.github.ascopes", "protobuf-maven-plugin")
                    .displayName("protobuf-maven-plugin")
                    .addRule(PluginConfigResolver.singleValueToSpec("protoc", "com.google.protobuf", "protoc", "exe"))
                    .addRule(PluginConfigResolver.filteredGavListToSpecs(
                            "plugins", "plugin", "kind", "binary-maven", "exe"))
                    .build());

    /**
     * This visitor is used to traverse the dependency graph and add the edges to the graph.
     */
    private static final class GraphBuildingNodeVisitor implements DependencyNodeVisitor {
        private final MutableGraph<DependencyNode> graph;

        /**
         * Create a new instance of the visitor.
         *
         * @param graph The graph to add the edges to.
         */
        private GraphBuildingNodeVisitor(MutableGraph<DependencyNode> graph) {
            this.graph = graph;
        }

        @Override
        public boolean visit(DependencyNode node) {
            node.getChildren().forEach(v -> graph.putEdge(node, v));
            return true;
        }

        @Override
        public boolean endVisit(DependencyNode node) {
            return true;
        }
    }

    /**
     * Generate a lock file for a project.
     *
     * @param project The project to generate a lock file for.
     * @return A lock file for the project.
     */
    public static Path getLockFilePath(MavenProject project, String lockfileName) {
        return Path.of(project.getBasedir().getAbsolutePath(), lockfileName);
    }

    private LockFileFacade() {
        // Prevent instantiation
    }

    /**
     * Generate a lock file for a project. This method is responsible for generating the lock file for a project. It uses the dependency collector to generate the dependency graph and then resolves the dependencies.
     *
     * @param session                    The maven session.
     * @param project                    The project to generate a lock file for.
     * @param dependencyCollectorBuilder The dependency collector builder to use for generating the dependency graph.
     * @param checksumCalculator         The checksum calculator to use for calculating the checksums of the artifacts.
     * @param metadata                   The metadata to include in the lock file.
     * @param repositorySystem           The repository system for resolving artifacts.
     * @return A lock file for the project.
     */
    public static LockFile generateLockFileFromProject(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            MetaData metadata,
            RepositorySystem repositorySystem,
            List<SpecialPluginResolver> additionalResolvers) {
        List<SpecialPluginResolver> allResolvers = new ArrayList<>(PLUGIN_RESOLVERS);
        allResolvers.addAll(additionalResolvers);
        PluginLogManager.getLog().info(String.format("Generating lock file for project %s", project.getArtifactId()));

        // Phase 1: attach a recording RepositoryListener to a mutable session copy.
        // The listener captures every artifact Aether resolves during dependency collection,
        // which is the basis for ExtraArtifactResolver finding implicit artifacts later.
        ExtraArtifactResolver.Tracker extraTracker = ExtraArtifactResolver.createTracker(session, project);
        DefaultRepositorySystemSession mutableSession = extraTracker.getMutableSession();
        session.getProjectBuildingRequest().setRepositorySession(mutableSession);

        Set<MavenPlugin> plugins = new TreeSet<>();
        if (metadata.getConfig().isIncludeMavenPlugins()) {
            plugins = getAllPlugins(project, session, dependencyCollectorBuilder, checksumCalculator, allResolvers);
        }

        Set<MavenExtension> extensions =
                getAllExtensions(project, session, dependencyCollectorBuilder, checksumCalculator, repositorySystem);

        // Get all the artifacts for the dependencies in the project
        DependencyGraph dependencyGraph = createDependencyGraph(
                project,
                session,
                project.getRemoteArtifactRepositories(),
                dependencyCollectorBuilder,
                checksumCalculator,
                null,
                metadata.getConfig().isReduced());

        var roots = dependencyGraph.getRoots();

        // Force-resolve annotation processor artifacts (and other forceDependencyPopulation resolvers)
        // as standalone roots so their full unmediated transitive closure is captured.
        roots.addAll(resolveSpecialPluginDependencies(
                project, session, dependencyCollectorBuilder, checksumCalculator, allResolvers));

        // Collect platform artifact specs from all applicable resolvers and resolve them
        // to platform-classifier DependencyNode entries (e.g. protoc:linux-x86_64).
        List<String> platformSpecs = new ArrayList<>();
        for (SpecialPluginResolver resolver : PLUGIN_RESOLVERS) {
            if (!resolver.isApplicable(project)) continue;
            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            platformSpecs.addAll(result.getPlatformArtifactSpecs());
        }
        if (!platformSpecs.isEmpty()) {
            String osClassifier = PlatformArtifactResolver.detectOsClassifier(project);
            if (osClassifier != null) {
                roots.addAll(PlatformArtifactResolver.resolve(platformSpecs, osClassifier, checksumCalculator));
            } else {
                PluginLogManager.getLog().warn(String.format(
                        "PlatformArtifacts: found %d spec(s) but could not determine OS classifier — skipping",
                        platformSpecs.size()));
            }
        }

        var pom = constructRecursivePom(project, session, checksumCalculator);

        resolveParentsAndBomsForDependencies(dependencyGraph, session, project, checksumCalculator);
        var boms = resolveBoms(session, project, checksumCalculator);

        // Phase 2: extract artifacts that Aether resolved but that don't appear in the dependency
        // graph, plugin list, or any other lockfile section. These are implicit runtime requirements
        // (e.g. Maven core extensions, wagon providers) needed for a hermetic offline build.
        Set<String> alreadyRecordedGavts = buildRecordedGavts(dependencyGraph, plugins, boms);
        List<io.github.chains_project.maven_lockfile.graph.DependencyNode> extraNodes =
                ExtraArtifactResolver.extractExtras(extraTracker, alreadyRecordedGavts, checksumCalculator);
        roots.addAll(extraNodes);

        // Write tracker JSON so all captured artifacts are available for diagnostics.
        java.io.File trackerOutputFile = new java.io.File(
                session.getRequest().getMultiModuleProjectDirectory(),
                ".mvn/tracker-artifacts.json");
        extraTracker.writeToFile(trackerOutputFile);

        // Resolve P2/OSGi dependencies for Tycho projects (no-op for non-Tycho projects).
        List<P2DependencyNode> p2Dependencies = Collections.emptyList();
        List<P2Repository> p2Repositories = Collections.emptyList();
        if (P2Resolver.isTychoProject(project)) {
            PluginLogManager.getLog().info("Tycho project detected — resolving P2 dependencies from .target files");
            P2Resolver.P2ResolverResult p2Result = P2Resolver.resolve(project);
            p2Dependencies = p2Result.getArtifacts();
            p2Repositories = p2Result.getRepositories();
            PluginLogManager.getLog().info(String.format(
                    "P2: resolved %d artifact(s) from %d repository(ies)",
                    p2Dependencies.size(), p2Repositories.size()));
        }

        return new LockFile(
                GroupId.of(project.getGroupId()),
                ArtifactId.of(project.getArtifactId()),
                VersionNumber.of(project.getVersion()),
                pom,
                roots,
                plugins,
                extensions,
                metadata,
                boms,
                p2Dependencies,
                p2Repositories);
    }

    private static Set<MavenExtension> getAllExtensions(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            RepositorySystem repositorySystem) {
        Set<MavenExtension> extensions = new TreeSet<>();

        List<Extension> buildExtensions = project.getBuildExtensions();
        if (buildExtensions == null || buildExtensions.isEmpty()) {
            return extensions;
        }

        RepositorySystemSession repoSession = session.getRepositorySession();
        List<RemoteRepository> repositories = project.getRemotePluginRepositories();

        // Collect all extensions as dependencies
        List<org.eclipse.aether.graph.Dependency> extensionDependencies = buildExtensions.stream()
                .map(ext -> toExtensionDependency(ext, repositorySystem, repoSession, repositories))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

        // Resolve all extensions and their dependencies in one call
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setDependencies(extensionDependencies);
        collectRequest.setRepositories(repositories);

        DependencyRequest dependencyRequest = new DependencyRequest();
        dependencyRequest.setCollectRequest(collectRequest);

        ProjectBuilder extensionProjectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());

        try {
            DependencyResult dependencyResult = repositorySystem.resolveDependencies(repoSession, dependencyRequest);

            // Process each resolved extension (direct dependencies from the root)
            for (org.eclipse.aether.graph.DependencyNode node :
                    dependencyResult.getRoot().getChildren()) {
                org.eclipse.aether.artifact.Artifact artifact = node.getArtifact();

                // Convert Aether artifact to Maven artifact for compatibility with checksumCalculator
                Artifact mavenArtifact = new org.apache.maven.artifact.DefaultArtifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        "compile",
                        artifact.getExtension(),
                        artifact.getClassifier(),
                        new DefaultArtifactHandler(artifact.getExtension()));
                mavenArtifact.setFile(artifact.getFile());

                RepositoryInformation repositoryInformation = checksumCalculator.getPluginResolvedField(mavenArtifact);

                Optional<MavenProject> extensionProjectOptional = extensionProjectBuilder.buildFromGav(
                        artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());

                // Resolve extension's transitive dependencies using the existing mechanism
                Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> transitiveDeps =
                        resolveComponentDependencies(
                                extensionProjectOptional.get(),
                                session,
                                project.getPluginArtifactRepositories(),
                                dependencyCollectorBuilder,
                                checksumCalculator,
                                Collections.emptyList());

                extensions.add(new MavenExtension(
                        GroupId.of(artifact.getGroupId()),
                        ArtifactId.of(artifact.getArtifactId()),
                        VersionNumber.of(artifact.getVersion()),
                        checksumCalculator.calculatePluginChecksum(mavenArtifact),
                        checksumCalculator.getChecksumAlgorithm(),
                        repositoryInformation.getResolvedUrl(),
                        repositoryInformation.getRepositoryId(),
                        transitiveDeps));
            }
        } catch (DependencyResolutionException e) {
            PluginLogManager.getLog().warn("Failed to resolve extension dependencies", e);
        }

        return extensions;
    }

    private static Optional<org.eclipse.aether.graph.Dependency> toExtensionDependency(
            Extension extension,
            RepositorySystem repositorySystem,
            RepositorySystemSession repoSession,
            List<RemoteRepository> repositories) {
        String version = extension.getVersion();
        if (version == null || version.isBlank()) {
            try {
                VersionRequest request = new VersionRequest(
                        new org.eclipse.aether.artifact.DefaultArtifact(
                                extension.getGroupId(), extension.getArtifactId(), "jar", "RELEASE"),
                        repositories,
                        null);
                version = repositorySystem.resolveVersion(repoSession, request).getVersion();
                PluginLogManager.getLog()
                        .warn(String.format(
                                "Extension %s:%s has no explicit version; resolved to %s",
                                extension.getGroupId(), extension.getArtifactId(), version));
            } catch (VersionResolutionException e) {
                PluginLogManager.getLog()
                        .warn(String.format(
                                "Skipping extension %s:%s: no version declared and could not resolve one",
                                extension.getGroupId(), extension.getArtifactId()));
                return Optional.empty();
            }
        }
        org.eclipse.aether.artifact.Artifact artifact = new org.eclipse.aether.artifact.DefaultArtifact(
                extension.getGroupId(), extension.getArtifactId(), "jar", version);
        return Optional.of(new org.eclipse.aether.graph.Dependency(artifact, JavaScopes.RUNTIME));
    }

    private static void resolveParentsAndBomsForDependencies(
            DependencyGraph graph,
            MavenSession session,
            MavenProject rootProject,
            AbstractChecksumCalculator checksumCalculator) {
        ProjectBuilder builder = new ProjectBuilder(session, rootProject.getRemoteArtifactRepositories());
        BomResolver bomResolver =
                new BomResolver(session, rootProject.getRemoteArtifactRepositories(), checksumCalculator);

        graph.getDependencySet().forEach(node -> {
            var projectOptional = builder.buildFromGav(
                    node.getGroupId().getValue(),
                    node.getArtifactId().getValue(),
                    node.getVersion().getValue());

            if (projectOptional.isEmpty()) {
                PluginLogManager.getLog()
                        .warn(String.format(
                                "Could not build project for dependency %s. Skipping parent and BOM resolution.",
                                node));
                return;
            }
            var mavenProject = projectOptional.get();

            if (mavenProject.hasParent()) {
                PluginLogManager.getLog().debug(String.format("Writting parent POM for dependency %s", node));

                // Optimization, not to resolve parent if already resolved
                // It has to be written in full form before lockfile v2
                Pom pom = constructRecursivePom(mavenProject.getParent(), session, checksumCalculator);
                node.setParentPom(pom);
            }

            Set<Pom> boms = bomResolver.resolveForProject(mavenProject);
            if (!boms.isEmpty()) {
                node.setBoms(boms);
            }
        });
    }

    private static Set<MavenPlugin> getAllPlugins(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            List<SpecialPluginResolver> resolvers) {
        Set<MavenPlugin> plugins = new TreeSet<>();

        // Build a map of user-declared plugin dependencies (mutable lists so resolvers can inject)
        // Key: groupId:artifactId, Value: list of user-declared dependencies
        Map<String, List<Dependency>> userPluginDependencies = new HashMap<>();
        if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
            for (Plugin plugin : project.getBuild().getPlugins()) {
                String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                if (plugin.getDependencies() != null
                        && !plugin.getDependencies().isEmpty()) {
                    userPluginDependencies.put(key, new ArrayList<>(plugin.getDependencies()));
                }
            }
        }

        // Run all registered special plugin resolvers to inject additional plugin dependencies
        for (SpecialPluginResolver resolver : resolvers) {
            if (!resolver.isApplicable(project)) continue;
            PluginLogManager.getLog().info(resolver.getDisplayName() + " detected — running special plugin resolver");
            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            if (result.isEmpty()) continue;
            for (Map.Entry<String, List<Dependency>> entry :
                    result.getPluginDependencies().entrySet()) {
                String pluginKey = entry.getKey();
                List<Dependency> existing = userPluginDependencies.computeIfAbsent(pluginKey, k -> new ArrayList<>());
                Set<String> existingGas = new HashSet<>();
                for (Dependency d : existing) existingGas.add(d.getGroupId() + ":" + d.getArtifactId());
                for (Dependency dep : entry.getValue()) {
                    if (existingGas.add(dep.getGroupId() + ":" + dep.getArtifactId())) {
                        existing.add(dep);
                    }
                }
            }
            // Platform artifact specs are collected and resolved by the caller
            // (generateLockFileFromProject) after all plugin dependencies are gathered.
        }

        ProjectBuilder projectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());

        for (Artifact pluginArtifact : project.getPluginArtifacts()) {
            RepositoryInformation repositoryInformation = checksumCalculator.getPluginResolvedField(pluginArtifact);
            String pluginKey = pluginArtifact.getGroupId() + ":" + pluginArtifact.getArtifactId();
            List<Dependency> userDeclaredDeps = userPluginDependencies.getOrDefault(pluginKey, Collections.emptyList());

            Optional<MavenProject> pluginProjectOptional = projectBuilder.buildFromGav(
                    pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(), pluginArtifact.getBaseVersion());

            if (pluginProjectOptional.isEmpty()) {
                PluginLogManager.getLog().warn(String.format("Could not build project for plugin %s", pluginArtifact));
                continue;
            }
            MavenProject pluginProject = pluginProjectOptional.get();

            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> pluginDependencies =
                    resolveComponentDependencies(
                            pluginProject,
                            session,
                            project.getPluginArtifactRepositories(),
                            dependencyCollectorBuilder,
                            checksumCalculator,
                            userDeclaredDeps);

            Pom parent = resolvePluginParents(pluginProject, session, checksumCalculator);

            plugins.add(new MavenPlugin(
                    GroupId.of(pluginArtifact.getGroupId()),
                    ArtifactId.of(pluginArtifact.getArtifactId()),
                    VersionNumber.of(pluginArtifact.getVersion()),
                    repositoryInformation.getResolvedUrl(),
                    repositoryInformation.getRepositoryId(),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksumCalculator.calculatePluginChecksum(pluginArtifact),
                    pluginDependencies,
                    parent));
        }
        return plugins;
    }

    private static Pom resolvePluginParents(
            MavenProject pluginProject, MavenSession session, AbstractChecksumCalculator checksumCalculator) {
        if (!pluginProject.hasParent()) {
            return null;
        }
        return constructRecursivePom(pluginProject.getParent(), session, checksumCalculator);
    }

    /**
     * Resolve the dependencies of a Maven plugin.
     *
     * @param pluginProject              The plugin project to resolve dependencies for
     * @param session                    The Maven session
     * @param repositories               The repositories to use for resolving dependencies
     * @param dependencyCollectorBuilder The dependency collector builder
     * @param checksumCalculator         The checksum calculator
     * @param userDeclaredDeps           User-declared dependencies for this plugin (from the project's pom.xml)
     * @return A set of dependency nodes representing the plugin's dependencies
     */
    private static Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> resolveComponentDependencies(
            MavenProject pluginProject,
            MavenSession session,
            List<ArtifactRepository> repositories,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            List<Dependency> userDeclaredDeps) {
        PluginLogManager.getLog()
                .debug(String.format("Attempting to resolve dependencies for plugin %s", pluginProject.getArtifact()));
        try {

            int declaredDeps = pluginProject.getDependencies() != null
                    ? pluginProject.getDependencies().size()
                    : 0;
            PluginLogManager.getLog()
                    .debug(String.format(
                            "Built plugin project %s with %d declared dependencies",
                            pluginProject.getArtifact(), declaredDeps));

            // Merge user-declared dependencies into the plugin project
            // User-declared dependencies override the plugin's default dependencies (e.g., scope changes)
            if (!userDeclaredDeps.isEmpty()) {
                List<Dependency> pluginDeps = new ArrayList<>(pluginProject.getDependencies());
                // Build a map of existing dependencies for quick lookup
                Map<String, Dependency> existingDepsMap = new HashMap<>();
                for (Dependency dep : pluginDeps) {
                    String key = dep.getGroupId() + ":" + dep.getArtifactId();
                    existingDepsMap.put(key, dep);
                }

                for (Dependency userDep : userDeclaredDeps) {
                    String key = userDep.getGroupId() + ":" + userDep.getArtifactId();
                    if (existingDepsMap.containsKey(key)) {
                        // Replace existing dependency with user-declared one (overrides scope, version, etc.)
                        pluginDeps.remove(existingDepsMap.get(key));
                        PluginLogManager.getLog()
                                .debug(String.format(
                                        "Overriding plugin dependency %s with user-declared dependency (scope: %s -> %s)",
                                        key, existingDepsMap.get(key).getScope(), userDep.getScope()));
                    } else {
                        PluginLogManager.getLog()
                                .debug(String.format(
                                        "Adding user-declared dependency %s to plugin %s",
                                        key, pluginProject.getArtifact()));
                    }
                    pluginDeps.add(userDep);
                }
                pluginProject.setDependencies(pluginDeps);
                PluginLogManager.getLog()
                        .debug(String.format(
                                "Plugin %s now has %d dependencies after merging user-declared dependencies",
                                pluginProject.getArtifact(), pluginDeps.size()));
            }

            // Filter artifacts to "compile+runtime" scopes. Maven plugins require their runtime
            // scope dependencies to be present alongside any compile-time dependencies.
            // Test scope dependencies of plugins should be excluded.
            ArtifactFilter filter = new ScopeArtifactFilter("compile+runtime");
            DependencyGraph dependencyGraph = createDependencyGraph(
                    pluginProject,
                    session,
                    repositories,
                    dependencyCollectorBuilder,
                    checksumCalculator,
                    filter,
                    false);

            resolveParentsAndBomsForDependencies(dependencyGraph, session, pluginProject, checksumCalculator);

            // Get root dependency nodes (excluding the plugin project itself)
            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> roots = dependencyGraph.getRoots();
            PluginLogManager.getLog()
                    .info(String.format(
                            "Resolved %4d dependencies for plugin %s", roots.size(), pluginProject.getArtifact()));
            return roots;

        } catch (Exception e) {
            PluginLogManager.getLog()
                    .warn(
                            String.format("Could not resolve dependencies for plugin %s", pluginProject.getArtifact()),
                            e);
            return Collections.emptySet();
        }
    }

    private static DependencyGraph createDependencyGraph(
            MavenProject project,
            MavenSession session,
            List<ArtifactRepository> repositories,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            ArtifactFilter filter,
            boolean reduced) {
        try {
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);
            buildingRequest.setRemoteRepositories(repositories);

            DependencyNode rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, filter);

            MutableGraph<DependencyNode> graph = GraphBuilder.directed().build();
            rootNode.accept(new GraphBuildingNodeVisitor(graph));

            PluginLogManager.getLog()
                    .info(String.format(
                            "Resolved %4d dependencies for project %s",
                            graph.nodes().size(), project.getArtifactId()));

            return DependencyGraph.of(graph, checksumCalculator, reduced);
        } catch (DependencyCollectorBuilderException e) {
            PluginLogManager.getLog().warn("Could not generate graph", e);
            return DependencyGraph.of(GraphBuilder.directed().build(), checksumCalculator, reduced);
        }
    }

    /**
     * Construct a Pom object containing a full tree of its parent POM references. These parent
     * POMs may be relative to the project being built, or are specified from an external POM.
     */
    private static Pom constructRecursivePom(
            MavenProject initialProject, MavenSession session, AbstractChecksumCalculator checksumCalculator) {
        String checksumAlgorithm = checksumCalculator.getChecksumAlgorithm();

        BomResolver bomResolver =
                new BomResolver(session, initialProject.getRemoteArtifactRepositories(), checksumCalculator);
        List<MavenProject> recursiveProjects = new ArrayList<>();
        MavenProject currentProject = initialProject;
        recursiveProjects.add(currentProject);
        while (currentProject.hasParent()) {
            currentProject = currentProject.getParent();
            recursiveProjects.add(currentProject);
        }

        @SuppressWarnings("deprecation")
        Path localRepoBasePath =
                session.getRepositorySession().getLocalRepository().getBasedir().toPath();

        Pom lastPom = null;
        Collections.reverse(recursiveProjects);
        for (MavenProject project : recursiveProjects) {
            boolean cachedInLocalRepo =
                    project.getFile() != null && project.getFile().toPath().startsWith(localRepoBasePath);
            boolean isExternalPom = project.getFile() == null || cachedInLocalRepo;

            String relativePath = isExternalPom
                    ? null
                    : initialProject
                            .getBasedir()
                            .toPath()
                            .relativize(project.getFile().toPath())
                            .toString();
            String checksum;
            ResolvedUrl resolved = null;
            RepositoryId repoId = null;
            if (isExternalPom) {
                // External POM (not in project directory) - get repository information
                Artifact artifact = project.getArtifact();
                // Use an explicit POM handler so getArtifactHandler().getExtension() reliably
                // returns "pom", which is required for parsing _remote.repositories correctly.
                Artifact pomArtifact = new DefaultArtifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        artifact.getScope(),
                        "pom",
                        artifact.getClassifier(),
                        new org.apache.maven.artifact.handler.DefaultArtifactHandler("pom"));
                if (cachedInLocalRepo) {
                    // Pre-set the file so getArtifactResolvedField can read _remote.repositories
                    // even if the POM-type dependency resolution fails
                    pomArtifact.setFile(project.getFile());
                    checksum = checksumCalculator.calculatePomChecksum(
                            project.getFile().toPath());
                } else {
                    checksum = checksumCalculator.calculateArtifactChecksum(pomArtifact);
                }
                RepositoryInformation repoInfo = checksumCalculator.getArtifactResolvedField(pomArtifact);
                resolved = repoInfo.getResolvedUrl();
                repoId = repoInfo.getRepositoryId();
            } else {
                checksum = checksumCalculator.calculatePomChecksum(
                        project.getFile().toPath());
            }

            Set<Pom> boms = bomResolver.resolveForProject(project);
            lastPom = new Pom(
                    GroupId.of(project.getGroupId()),
                    ArtifactId.of(project.getArtifactId()),
                    VersionNumber.of(project.getVersion()),
                    relativePath,
                    resolved,
                    repoId,
                    checksumAlgorithm,
                    checksum,
                    lastPom);
            if (!boms.isEmpty()) {
                lastPom.setBoms(boms);
            }
        }

        return lastPom;
    }

    /**
     * For every {@link SpecialPluginResolver} that returns {@code true} from
     * {@link SpecialPluginResolver#forceDependencyPopulation()}, resolves each discovered
     * dependency as a <em>standalone root</em> — bypassing Maven's plugin-context conflict
     * mediation so the full, unmediated transitive closure of each artifact is captured.
     *
     * <p>Needed for annotation processors declared in {@code <annotationProcessorPaths>} whose
     * classloader is independent of the project's dependency graph — every artifact must be
     * present regardless of the project-level conflict winner.
     */
    private static Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> resolveSpecialPluginDependencies(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            List<SpecialPluginResolver> resolvers) {
        Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> allRoots = new TreeSet<>(Comparator.comparing(
                io.github.chains_project.maven_lockfile.graph.DependencyNode::getComparatorString));
        ProjectBuilder projectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());

        for (SpecialPluginResolver resolver : resolvers) {
            if (!resolver.isApplicable(project)) continue;
            if (!resolver.forceDependencyPopulation()) continue;

            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            if (result.isEmpty()) continue;

            PluginLogManager.getLog()
                    .info(String.format(
                            "%s: force-resolving discovered artifacts as standalone roots", resolver.getDisplayName()));

            for (List<Dependency> deps : result.getPluginDependencies().values()) {
                for (Dependency dep : deps) {
                    Optional<MavenProject> depProjectOpt =
                            projectBuilder.buildFromGav(dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
                    if (depProjectOpt.isEmpty()) {
                        PluginLogManager.getLog()
                                .warn(String.format(
                                        "%s: could not build project for %s:%s:%s — skipping",
                                        resolver.getDisplayName(),
                                        dep.getGroupId(),
                                        dep.getArtifactId(),
                                        dep.getVersion()));
                        continue;
                    }
                    Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> nodes =
                            resolveComponentDependencies(
                                    depProjectOpt.get(),
                                    session,
                                    project.getPluginArtifactRepositories(),
                                    dependencyCollectorBuilder,
                                    checksumCalculator,
                                    Collections.emptyList());
                    allRoots.addAll(nodes);
                    PluginLogManager.getLog()
                            .debug(String.format(
                                    "%s: added %d node(s) for %s:%s:%s",
                                    resolver.getDisplayName(),
                                    nodes.size(),
                                    dep.getGroupId(),
                                    dep.getArtifactId(),
                                    dep.getVersion()));
                }
            }
        }
        return allRoots;
    }

    /**
     * Resolve the BOM POMs for the current project.
     *
     * @param session            The Maven session
     * @param rootProject        The current Maven project (for repository configuration)
     * @param checksumCalculator The checksum calculator
     * @return A set of BOM POMs
     */
    private static Set<Pom> resolveBoms(
            MavenSession session, MavenProject rootProject, AbstractChecksumCalculator checksumCalculator) {
        BomResolver bomResolver =
                new BomResolver(session, rootProject.getRemoteArtifactRepositories(), checksumCalculator);
        return bomResolver.resolveForProject(rootProject);
    }

    /**
     * Builds a set of {@code groupId:artifactId:version:type} keys for every artifact already
     * recorded in the lockfile, used by {@link ExtraArtifactResolver#extractExtras} to avoid
     * duplicating artifacts that are already captured in the dependency graph, plugin list, or BOMs.
     */
    private static Set<String> buildRecordedGavts(
            DependencyGraph dependencyGraph,
            Set<MavenPlugin> plugins,
            Set<Pom> boms) {
        Set<String> gavts = new HashSet<>();

        // All nodes in the dependency graph (roots + transitive children)
        for (io.github.chains_project.maven_lockfile.graph.DependencyNode node
                : dependencyGraph.getDependencySet()) {
            gavts.add(node.getGroupId() + ":" + node.getArtifactId()
                    + ":" + node.getVersion() + ":" + node.getType());
        }

        // Plugin JARs recorded in the plugin section
        for (MavenPlugin plugin : plugins) {
            gavts.add(plugin.getGroupId() + ":" + plugin.getArtifactId()
                    + ":" + plugin.getVersion() + ":jar");
            for (io.github.chains_project.maven_lockfile.graph.DependencyNode dep
                    : plugin.getDependencies()) {
                gavts.add(dep.getGroupId() + ":" + dep.getArtifactId()
                        + ":" + dep.getVersion() + ":" + dep.getType());
            }
        }

        // BOM POMs recorded in the boms section
        for (Pom bom : boms) {
            gavts.add(bom.getGroupId() + ":" + bom.getArtifactId()
                    + ":" + bom.getVersion() + ":pom");
        }

        return gavts;
    }
}
