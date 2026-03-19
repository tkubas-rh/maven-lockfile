package io.github.chains_project.maven_lockfile.graph;

import com.google.common.graph.Graph;
import com.google.common.graph.MutableGraph;
import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.checksum.RepositoryInformation;
import io.github.chains_project.maven_lockfile.data.*;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.shared.dependency.graph.internal.SpyingDependencyNodeUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DependencyGraph {

    private final Set<DependencyNode> graph;
    private final Map<NodeId, DependencyNode> nodeIndex;

    public Set<DependencyNode> getRoots() {
        return graph.stream()
                .filter(node -> node.getParent() == null)
                .sorted(Comparator.comparing(DependencyNode::getComparatorString))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private DependencyGraph(Set<DependencyNode> graph) {
        this.graph = graph == null ? Collections.emptySet() : graph;

        this.nodeIndex = this.graph.stream()
                .collect(Collectors.toMap(
                        DependencyNode::getNodeId,
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    public Set<DependencyNode> getGraph() {
        return graph;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof DependencyGraph)) return false;
        DependencyGraph that = (DependencyGraph) o;
        return Objects.equals(graph, that.graph);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(graph);
    }

    public Optional<DependencyNode> getParentForNode(DependencyNode node) {
        return Optional.ofNullable(nodeIndex.get(node.getParent()));
    }

    public static DependencyGraph of(
            MutableGraph<org.apache.maven.shared.dependency.graph.DependencyNode> graph,
            AbstractChecksumCalculator calc,
            boolean reduced) {

        List<org.apache.maven.shared.dependency.graph.DependencyNode> roots =
                graph.nodes().stream()
                        .filter(it -> graph.predecessors(it).isEmpty())
                        .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();
        List<Artifact> uniqueArtifacts = new ArrayList<>();

        for (org.apache.maven.shared.dependency.graph.DependencyNode node : graph.nodes()) {
            if (!graph.predecessors(node).isEmpty()) {
                Artifact a = node.getArtifact();
                String key = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion()
                        + ":" + (a.getClassifier() != null ? a.getClassifier() : "")
                        + ":" + a.getType();

                if (seen.add(key)) {
                    uniqueArtifacts.add(a);
                }
            }
        }

        calc.prewarmArtifactCache(uniqueArtifacts);

        Map<org.apache.maven.shared.dependency.graph.DependencyNode, DependencyNode> cache = new HashMap<>();

        Set<DependencyNode> nodes = new LinkedHashSet<>();

        for (org.apache.maven.shared.dependency.graph.DependencyNode root : roots) {
            Optional<DependencyNode> created =
                    createDependencyNode(root, graph, calc, true, reduced, cache);

            if (created.isPresent()) {
                nodes.add(created.get());
            }
        }

        Set<DependencyNode> dependencyRoots = nodes.stream()
                .flatMap(v -> v.getChildren().stream())
                .sorted(Comparator.comparing(DependencyNode::getComparatorString))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (DependencyNode v : dependencyRoots) {
            v.setParent(null);
        }

        return new DependencyGraph(dependencyRoots);
    }

    private static Optional<DependencyNode> createDependencyNode(
            org.apache.maven.shared.dependency.graph.DependencyNode node,
            Graph<org.apache.maven.shared.dependency.graph.DependencyNode> graph,
            AbstractChecksumCalculator calc,
            boolean isRoot,
            boolean reduce,
            Map<org.apache.maven.shared.dependency.graph.DependencyNode, DependencyNode> cache) {

        if (!isRoot) {
            DependencyNode cached = cache.get(node);
            if (cached != null) {
                return Optional.of(cached);
            }
        }

        Artifact artifact = node.getArtifact();

        var log = PluginLogManager.getLog();
        if (log.isDebugEnabled()) {
            log.debug("Creating dependency node for: " + node.toNodeString() + ", root: " + isRoot);
        }

        GroupId groupId = GroupId.of(artifact.getGroupId());
        ArtifactId artifactId = ArtifactId.of(artifact.getArtifactId());
        VersionNumber version = VersionNumber.of(artifact.getVersion());
        Classifier classifier = Classifier.of(artifact.getClassifier());
        ArtifactType type = ArtifactType.of(artifact.getType());

        String checksum = isRoot ? "" : calc.calculateArtifactChecksum(artifact);
        MavenScope scope = MavenScope.fromString(artifact.getScope());

        RepositoryInformation repositoryInformation =
                isRoot ? RepositoryInformation.Unresolved()
                        : calc.getArtifactResolvedField(artifact);

        Optional<String> winnerVersion = SpyingDependencyNodeUtils.getWinnerVersion(node);
        boolean included = !winnerVersion.isPresent();
        String baseVersion = included ? artifact.getVersion() : winnerVersion.get();

        if (reduce && !included) {
            return Optional.empty();
        }

        DependencyNode value = new DependencyNode(
                artifactId,
                groupId,
                version,
                classifier,
                type,
                scope,
                repositoryInformation.getResolvedUrl(),
                repositoryInformation.getRepositoryId(),
                calc.getChecksumAlgorithm(),
                checksum
        );

        value.id = new NodeId(groupId, artifactId, version);

        value.setSelectedVersion(baseVersion);
        value.setIncluded(included);

        if (!isRoot) {
            cache.put(node, value);
        }

        for (org.apache.maven.shared.dependency.graph.DependencyNode child : graph.successors(node)) {
            Optional<DependencyNode> created =
                    createDependencyNode(child, graph, calc, false, reduce, cache);

            if (created.isPresent()) {
                value.addChild(created.get());
            }
        }

        return Optional.of(value);
    }
}