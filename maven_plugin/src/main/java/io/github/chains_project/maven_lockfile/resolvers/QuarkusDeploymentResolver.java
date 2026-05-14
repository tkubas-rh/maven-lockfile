package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Discovers Quarkus deployment artifacts that {@code quarkus-maven-plugin:generate-code}
 * dynamically loads at runtime via its bootstrap mechanism.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Detect Quarkus project (presence of {@code quarkus-maven-plugin} in build plugins).
 *   <li>Infer the Quarkus platform version from the resolved {@code quarkus-core} artifact.
 *   <li>Add {@code quarkus-bom-quarkus-platform-properties} (type=properties) — required by the
 *       Quarkus bootstrap to locate the platform descriptor.
 *   <li>Scan every resolved project dependency JAR for
 *       {@code META-INF/quarkus-extension.properties}. If present, read the
 *       {@code deployment-artifact} property (format {@code groupId:artifactId:version}) and emit
 *       a corresponding {@link Dependency} so maven-lockfile records it.
 *   <li>For each discovered deployment artifact, read its POM from the local Maven repository to
 *       find optional ("conditional") {@code *-deployment} dependencies. The Quarkus bootstrap
 *       resolves these to check whether their runtime counterpart is present in the project —
 *       even when it is not, the runtime JAR must be available offline so the check can succeed.
 *       Both the optional deployment artifact and its runtime counterpart are added to the result.
 * </ol>
 *
 * <p>The returned list is injected as user-declared plugin dependencies for
 * {@code quarkus-maven-plugin} in {@code LockFileFacade.getAllPlugins()}, so the full transitive
 * closure of every deployment artifact is resolved and written to the lockfile — without any
 * manual changes to the project's {@code pom.xml}.
 */
public class QuarkusDeploymentResolver extends SpecialPluginResolver {

    private static final String QUARKUS_PLUGIN_ARTIFACT_ID = "quarkus-maven-plugin";
    private static final String QUARKUS_EXTENSION_PROPERTIES = "META-INF/quarkus-extension.properties";
    private static final String DEPLOYMENT_ARTIFACT_KEY = "deployment-artifact";
    private static final String PLATFORM_PROPERTIES_GROUP = "io.quarkus";
    private static final String PLATFORM_PROPERTIES_ARTIFACT = "quarkus-bom-quarkus-platform-properties";
    private static final String DEPLOYMENT_SUFFIX = "-deployment";

    public QuarkusDeploymentResolver() {}

    @Override
    public boolean isApplicable(MavenProject project) {
        return findPlugin(project, QUARKUS_PLUGIN_ARTIFACT_ID).isPresent();
    }

    @Override
    public String getDisplayName() {
        return "Quarkus";
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        List<Dependency> deps = discoverDeploymentDependencies(project, session);
        if (deps.isEmpty()) {
            return DiscoveryResult.empty();
        }
        String pluginKey = findPlugin(project, QUARKUS_PLUGIN_ARTIFACT_ID)
                .map(p -> p.getGroupId() + ":" + p.getArtifactId())
                .orElse("io.quarkus:" + QUARKUS_PLUGIN_ARTIFACT_ID);
        return DiscoveryResult.ofPluginDependencies(pluginKey, deps);
    }

    /**
     * Discovers deployment artifact dependencies that the Quarkus bootstrap will load dynamically
     * during {@code generate-code}. These are not declared in the project's {@code pom.xml} but
     * are required for a hermetic offline build.
     *
     * <p>In addition to primary deployment artifacts (from {@code META-INF/quarkus-extension.properties}),
     * also discovers optional "conditional" deployment artifacts declared in deployment POMs.
     * The Quarkus bootstrap resolves these at startup to determine whether the corresponding
     * runtime extension is active — even when it is not, the runtime JAR must be accessible
     * offline for the resolution check to succeed.
     *
     * @param project the Maven project
     * @param session the Maven session (used to locate the local repository for POM scanning)
     * @return list of deployment artifact {@link Dependency} objects to inject as plugin deps
     */
    public static List<Dependency> discoverDeploymentDependencies(MavenProject project, MavenSession session) {
        List<Dependency> deploymentDeps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        String quarkusVersion = findQuarkusVersion(project);
        addPlatformProperties(quarkusVersion, deploymentDeps, seen);

        List<Dependency> primaryDeps = discoverPrimaryDeploymentArtifacts(project, deploymentDeps, seen);
        PluginLogManager.getLog()
                .info(String.format("Quarkus: discovered %d primary deployment artifact(s)", primaryDeps.size()));

        String localRepoBase = session.getLocalRepository().getBasedir();
        int extraCount = discoverTransitiveDeploymentDeps(
                primaryDeps, localRepoBase, quarkusVersion, deploymentDeps, seen);
        PluginLogManager.getLog()
                .info(String.format("Quarkus: added %d extra artifact(s) from deployment POM scanning", extraCount));

        return deploymentDeps;
    }

    private static void addPlatformProperties(
            String quarkusVersion, List<Dependency> out, Set<String> seen) {
        if (quarkusVersion == null) {
            PluginLogManager.getLog()
                    .warn("Quarkus: could not determine Quarkus version — skipping platform-properties");
            return;
        }
        Dependency platformProps = createDependency(
                PLATFORM_PROPERTIES_GROUP, PLATFORM_PROPERTIES_ARTIFACT, quarkusVersion);
        platformProps.setType("properties");
        addIfAbsent(out, seen, platformProps);
        PluginLogManager.getLog()
                .info("Quarkus: adding platform-properties " + PLATFORM_PROPERTIES_ARTIFACT + ":" + quarkusVersion);
    }

    private static List<Dependency> discoverPrimaryDeploymentArtifacts(
            MavenProject project, List<Dependency> out, Set<String> seen) {
        List<Dependency> primaryDeps = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            Dependency dep = tryParseDeploymentArtifact(artifact);
            if (dep == null) continue;
            if (addIfAbsent(out, seen, dep)) {
                primaryDeps.add(dep);
                PluginLogManager.getLog()
                        .debug("Quarkus: discovered deployment artifact "
                                + dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion()
                                + " (from " + artifact.getArtifactId() + ")");
            }
        }
        return primaryDeps;
    }

    private static Dependency tryParseDeploymentArtifact(Artifact artifact) {
        File file = artifact.getFile();
        if (file == null || !file.exists() || !"jar".equals(artifact.getType())) {
            return null;
        }
        try {
            String gav = readDeploymentArtifact(file);
            if (gav == null) return null;

            String[] parts = gav.split(":");
            if (parts.length < 3) {
                PluginLogManager.getLog()
                        .warn("Quarkus: malformed deployment-artifact value in " + artifact + ": " + gav);
                return null;
            }
            return createDependency(parts[0], parts[1], parts[2]);
        } catch (IOException e) {
            PluginLogManager.getLog()
                    .debug("Quarkus: could not read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static int discoverTransitiveDeploymentDeps(
            List<Dependency> primaryDeps, String localRepoBase, String quarkusVersion,
            List<Dependency> out, Set<String> seen) {
        int extraCount = 0;
        Set<String> scanned = new HashSet<>();
        List<Dependency> toScan = new ArrayList<>(primaryDeps);

        while (!toScan.isEmpty()) {
            List<Dependency> nextScan = new ArrayList<>();
            for (Dependency dep : toScan) {
                String scanKey = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
                if (!scanned.add(scanKey)) continue;

                for (Dependency pomDep : discoverDeploymentPomDeps(dep, localRepoBase, quarkusVersion)) {
                    if (!addIfAbsent(out, seen, pomDep)) continue;
                    extraCount++;
                    PluginLogManager.getLog()
                            .debug("Quarkus: adding POM dep " + pomDep.getArtifactId()
                                    + ":" + pomDep.getVersion()
                                    + " (from " + dep.getArtifactId() + ")");
                    if (pomDep.getArtifactId().endsWith(DEPLOYMENT_SUFFIX)) {
                        nextScan.add(pomDep);
                    }
                }
            }
            toScan = nextScan;
        }
        return extraCount;
    }

    /**
     * Reads the POM of a deployment artifact from the local Maven repository and returns
     * additional dependencies that the Quarkus bootstrap needs but Maven's standard resolver
     * would miss.
     */
    private static List<Dependency> discoverDeploymentPomDeps(
            Dependency deploymentDep, String localRepoBase, String platformVersion) {
        File pomFile = localRepoPomFile(localRepoBase, deploymentDep);
        if (pomFile == null || !pomFile.exists()) {
            return List.of();
        }

        try {
            Document doc = parseXml(pomFile);
            return extractDeploymentDeps(doc, deploymentDep, platformVersion);
        } catch (Exception e) {
            PluginLogManager.getLog()
                    .debug("Quarkus: could not parse POM for " + deploymentDep.getArtifactId()
                            + ": " + e.getMessage());
            return List.of();
        }
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static List<Dependency> extractDeploymentDeps(
            Document doc, Dependency deploymentDep, String platformVersion) {
        List<Dependency> result = new ArrayList<>();
        NodeList depNodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            result.addAll(processPomDependency(
                    (Element) depNodes.item(i), deploymentDep, platformVersion));
        }
        return result;
    }

    private static List<Dependency> processPomDependency(
            Element depElem, Dependency deploymentDep, String platformVersion) {
        String groupId = childText(depElem, "groupId");
        String artifactId = childText(depElem, "artifactId");
        if (groupId == null || artifactId == null) return List.of();

        boolean isOptional = "true".equals(childText(depElem, "optional"));
        String rawVersion = childText(depElem, "version");
        boolean isVersionless = rawVersion == null || rawVersion.startsWith("${");

        if (isVersionless && !groupId.startsWith("io.quarkus")) return List.of();

        String version = resolveEffectiveVersion(
                rawVersion, isVersionless, platformVersion, deploymentDep.getVersion());

        if (isOptional && artifactId.endsWith(DEPLOYMENT_SUFFIX)) {
            String runtimeArtifactId =
                    artifactId.substring(0, artifactId.length() - DEPLOYMENT_SUFFIX.length());
            return List.of(
                    createDependency(groupId, artifactId, version),
                    createDependency(groupId, runtimeArtifactId, version));
        }

        if (isVersionless && !isOptional) {
            Dependency dep = createDependency(groupId, artifactId, version);
            String type = childText(depElem, "type");
            if (type != null) dep.setType(type);
            return List.of(dep);
        }

        return List.of();
    }

    private static String resolveEffectiveVersion(
            String rawVersion, boolean isVersionless, String platformVersion, String fallback) {
        String version = isVersionless ? platformVersion : rawVersion;
        if (version == null || version.startsWith("${")) {
            version = fallback;
        }
        return version;
    }

    private static File localRepoPomFile(String localRepoBase, Dependency dep) {
        String groupPath = dep.getGroupId().replace('.', File.separatorChar);
        String fileName = dep.getArtifactId() + "-" + dep.getVersion() + ".pom";
        return new File(
                localRepoBase,
                groupPath
                        + File.separator
                        + dep.getArtifactId()
                        + File.separator
                        + dep.getVersion()
                        + File.separator
                        + fileName);
    }

    private static String readDeploymentArtifact(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry(QUARKUS_EXTENSION_PROPERTIES);
            if (entry == null) return null;

            Properties props = new Properties();
            try (InputStream is = jar.getInputStream(entry)) {
                props.load(is);
            }
            return props.getProperty(DEPLOYMENT_ARTIFACT_KEY);
        }
    }

    private static String findQuarkusVersion(MavenProject project) {
        return project.getArtifacts().stream()
                .filter(a -> "io.quarkus".equals(a.getGroupId()) && "quarkus-core".equals(a.getArtifactId()))
                .map(Artifact::getVersion)
                .findFirst()
                .orElse(null);
    }

    private static String childText(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getParentNode() == parent) {
                return nl.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    private static boolean addIfAbsent(List<Dependency> list, Set<String> seen, Dependency dep) {
        String key = dep.getGroupId() + ":" + dep.getArtifactId();
        if (seen.add(key)) {
            list.add(dep);
            return true;
        }
        return false;
    }
}
