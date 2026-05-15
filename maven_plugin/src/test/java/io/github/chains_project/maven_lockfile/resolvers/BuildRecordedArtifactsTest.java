package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.checksum.FileSystemChecksumCalculator;
import io.github.chains_project.maven_lockfile.graph.DependencyNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildRecordedArtifactsTest {

    @TempDir
    Path tempDir;

    private final AbstractChecksumCalculator checksumCalculator =
            new FileSystemChecksumCalculator(null, null, null, "SHA-256");

    @Test
    void loadFromFile_parsesValidJson() throws IOException {
        String json = "[\n"
                + "  {\n"
                + "    \"url\": \"https://repo.maven.apache.org/maven2/com/example/lib/1.0/lib-1.0.jar\",\n"
                + "    \"groupId\": \"com.example\",\n"
                + "    \"artifactId\": \"lib\",\n"
                + "    \"version\": \"1.0\",\n"
                + "    \"classifier\": \"\",\n"
                + "    \"extension\": \"jar\"\n"
                + "  }\n"
                + "]";
        File jsonFile = writeJson(json);

        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(jsonFile, Set.of(), checksumCalculator, tempDir.toString());

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getGroupId().getValue()).isEqualTo("com.example");
        assertThat(nodes.get(0).getArtifactId().getValue()).isEqualTo("lib");
        assertThat(nodes.get(0).getVersion().getValue()).isEqualTo("1.0");
    }

    @Test
    void loadFromFile_deduplicatesAgainstExistingGavts() throws IOException {
        String json = "[\n"
                + "  {\n"
                + "    \"url\": \"https://repo/a\",\n"
                + "    \"groupId\": \"com.example\",\n"
                + "    \"artifactId\": \"lib\",\n"
                + "    \"version\": \"1.0\",\n"
                + "    \"classifier\": \"\",\n"
                + "    \"extension\": \"jar\"\n"
                + "  },\n"
                + "  {\n"
                + "    \"url\": \"https://repo/b\",\n"
                + "    \"groupId\": \"org.test\",\n"
                + "    \"artifactId\": \"other\",\n"
                + "    \"version\": \"2.0\",\n"
                + "    \"classifier\": \"\",\n"
                + "    \"extension\": \"jar\"\n"
                + "  }\n"
                + "]";
        File jsonFile = writeJson(json);

        Set<String> existing = new HashSet<>();
        existing.add("com.example:lib:1.0:jar");

        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(jsonFile, existing, checksumCalculator, tempDir.toString());

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getArtifactId().getValue()).isEqualTo("other");
    }

    @Test
    void loadFromFile_returnsEmptyForMissingFile() {
        File nonExistent = new File(tempDir.toFile(), "does-not-exist.json");
        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(nonExistent, Set.of(), checksumCalculator, tempDir.toString());
        assertThat(nodes).isEmpty();
    }

    @Test
    void loadFromFile_returnsEmptyForNull() {
        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(null, Set.of(), checksumCalculator, tempDir.toString());
        assertThat(nodes).isEmpty();
    }

    @Test
    void loadFromFile_handlesEmptyArray() throws IOException {
        File jsonFile = writeJson("[]");
        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(jsonFile, Set.of(), checksumCalculator, tempDir.toString());
        assertThat(nodes).isEmpty();
    }

    @Test
    void loadFromFile_handlesMalformedJson() throws IOException {
        File jsonFile = writeJson("not valid json {{{");
        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(jsonFile, Set.of(), checksumCalculator, tempDir.toString());
        assertThat(nodes).isEmpty();
    }

    @Test
    void loadFromFile_skipsEntriesWithMissingRequiredFields() throws IOException {
        String json = "[\n"
                + "  { \"groupId\": \"com.example\", \"artifactId\": \"lib\" },\n"
                + "  {\n"
                + "    \"url\": \"https://repo/a\",\n"
                + "    \"groupId\": \"com.example\",\n"
                + "    \"artifactId\": \"complete\",\n"
                + "    \"version\": \"1.0\",\n"
                + "    \"classifier\": \"\",\n"
                + "    \"extension\": \"jar\"\n"
                + "  }\n"
                + "]";
        File jsonFile = writeJson(json);
        List<DependencyNode> nodes =
                BuildRecordedArtifacts.loadFromFile(jsonFile, Set.of(), checksumCalculator, tempDir.toString());
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getArtifactId().getValue()).isEqualTo("complete");
    }

    private File writeJson(String content) throws IOException {
        Path file = tempDir.resolve("build-recorded-artifacts.json");
        Files.writeString(file, content);
        return file.toFile();
    }
}
