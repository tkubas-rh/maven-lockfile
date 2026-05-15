package io.github.chains_project.maven_lockfile.recorder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordedArtifactStore {

    private static final Set<String> ARTIFACT_EXTENSIONS =
            Set.of("jar", "pom", "war", "ear", "rar", "properties", "zip");

    private static final Map<String, RecordedArtifact> CAPTURED = new ConcurrentHashMap<>();

    private RecordedArtifactStore() {}

    public static boolean isRecordableExtension(String extension) {
        return ARTIFACT_EXTENSIONS.contains(extension);
    }

    public static void capture(RecordedArtifact artifact) {
        String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                + artifact.getVersion() + ":" + artifact.getExtension() + ":"
                + artifact.getClassifier();
        CAPTURED.putIfAbsent(key, artifact);
    }

    public static void flush(File outputFile) {
        outputFile.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (RecordedArtifact a : CAPTURED.values()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  {\n");
            sb.append("    \"url\": \"").append(esc(a.getUrl())).append("\",\n");
            sb.append("    \"groupId\": \"").append(esc(a.getGroupId())).append("\",\n");
            sb.append("    \"artifactId\": \"").append(esc(a.getArtifactId())).append("\",\n");
            sb.append("    \"version\": \"").append(esc(a.getVersion())).append("\",\n");
            sb.append("    \"classifier\": \"").append(esc(a.getClassifier())).append("\",\n");
            sb.append("    \"extension\": \"").append(esc(a.getExtension())).append("\"\n");
            sb.append("  }");
        }
        sb.append("\n]");
        try {
            Files.writeString(outputFile.toPath(), sb.toString());
        } catch (IOException e) {
            System.err.println("[maven-lockfile-recorder] Could not write " + outputFile + ": " + e.getMessage());
        }
    }

    public static Collection<RecordedArtifact> getAll() {
        return CAPTURED.values();
    }

    public static int size() {
        return CAPTURED.size();
    }

    public static void reset() {
        CAPTURED.clear();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
