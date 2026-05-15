package io.github.chains_project.maven_lockfile.resolvers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.data.ArtifactId;
import io.github.chains_project.maven_lockfile.data.ArtifactType;
import io.github.chains_project.maven_lockfile.data.Classifier;
import io.github.chains_project.maven_lockfile.data.GroupId;
import io.github.chains_project.maven_lockfile.data.RepositoryId;
import io.github.chains_project.maven_lockfile.data.ResolvedUrl;
import io.github.chains_project.maven_lockfile.data.VersionNumber;
import io.github.chains_project.maven_lockfile.graph.DependencyNode;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class BuildRecordedArtifacts {

    private BuildRecordedArtifacts() {}

    public static List<DependencyNode> loadFromFile(
            File recordedArtifactsFile,
            Set<String> alreadyRecordedGavts,
            AbstractChecksumCalculator checksumCalculator,
            String localRepoBase) {
        if (recordedArtifactsFile == null || !recordedArtifactsFile.exists()) {
            return Collections.emptyList();
        }

        String json;
        try {
            json = Files.readString(recordedArtifactsFile.toPath());
        } catch (IOException e) {
            PluginLogManager.getLog()
                    .warn("BuildRecordedArtifacts: could not read " + recordedArtifactsFile + ": " + e.getMessage());
            return Collections.emptyList();
        }

        JsonArray array;
        try {
            array = new Gson().fromJson(json, JsonArray.class);
        } catch (Exception e) {
            PluginLogManager.getLog()
                    .warn("BuildRecordedArtifacts: malformed JSON in " + recordedArtifactsFile + ": " + e.getMessage());
            return Collections.emptyList();
        }
        if (array == null) return Collections.emptyList();

        List<DependencyNode> nodes = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonObject()) continue;
            JsonObject obj = elem.getAsJsonObject();
            String groupId = getString(obj, "groupId");
            String artifactId = getString(obj, "artifactId");
            String version = getString(obj, "version");
            String classifier = getString(obj, "classifier");
            String extension = getString(obj, "extension");
            String url = getString(obj, "url");

            if (groupId == null || artifactId == null || version == null || extension == null) continue;

            String gavt = groupId + ":" + artifactId + ":" + version + ":" + extension;
            if (alreadyRecordedGavts.contains(gavt)) continue;

            String checksum = computeChecksum(
                    groupId, artifactId, version, classifier, extension, localRepoBase, checksumCalculator);

            String repoId = extractRepoId(url);

            DependencyNode node = DependencyNode.ofPlatformArtifact(
                    ArtifactId.of(artifactId),
                    GroupId.of(groupId),
                    VersionNumber.of(version),
                    Classifier.of(classifier != null ? classifier : ""),
                    ArtifactType.of(extension),
                    ResolvedUrl.of(url != null ? url : ""),
                    RepositoryId.of(repoId),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksum);

            nodes.add(node);
        }

        PluginLogManager.getLog()
                .info(String.format(
                        "BuildRecordedArtifacts: loaded %d extra artifact(s) from %s",
                        nodes.size(), recordedArtifactsFile));
        return nodes;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        String val = e.getAsString();
        return val.isEmpty() ? null : val;
    }

    private static String computeChecksum(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String extension,
            String localRepoBase,
            AbstractChecksumCalculator checksumCalculator) {
        String groupPath = groupId.replace('.', File.separatorChar);
        String fileName = artifactId + "-" + version;
        if (classifier != null && !classifier.isEmpty()) {
            fileName += "-" + classifier;
        }
        fileName += "." + extension;

        Path artifactPath = Path.of(localRepoBase, groupPath, artifactId, version, fileName);

        if (Files.exists(artifactPath)) {
            return checksumCalculator.calculatePomChecksum(artifactPath);
        }
        return "";
    }

    private static String extractRepoId(String url) {
        if (url == null || url.isEmpty()) return "unknown";
        if (url.contains("repo.maven.apache.org") || url.contains("repo1.maven.org")) return "central";
        return "unknown";
    }
}
