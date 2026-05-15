package io.github.chains_project.maven_lockfile.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordedArtifactStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RecordedArtifactStore.reset();
    }

    @Test
    void captureAndSize() {
        assertThat(RecordedArtifactStore.size()).isEqualTo(0);

        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a"));
        assertThat(RecordedArtifactStore.size()).isEqualTo(1);

        RecordedArtifactStore.capture(artifact("com.example", "lib-b", "2.0", "", "jar", "https://repo/b"));
        assertThat(RecordedArtifactStore.size()).isEqualTo(2);
    }

    @Test
    void deduplication() {
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a"));
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a-dup"));
        assertThat(RecordedArtifactStore.size()).isEqualTo(1);
    }

    @Test
    void sameGavDifferentExtensionIsNotDeduped() {
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a.jar"));
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "pom", "https://repo/a.pom"));
        assertThat(RecordedArtifactStore.size()).isEqualTo(2);
    }

    @Test
    void reset() {
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a"));
        RecordedArtifactStore.reset();
        assertThat(RecordedArtifactStore.size()).isEqualTo(0);
    }

    @Test
    void flushWritesValidJson() throws IOException {
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a"));
        RecordedArtifactStore.capture(artifact("org.test", "lib-b", "2.0", "sources", "jar", "https://repo/b"));

        File outputFile = tempDir.resolve("output.json").toFile();
        RecordedArtifactStore.flush(outputFile);

        assertThat(outputFile).exists();
        String json = Files.readString(outputFile.toPath());
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("\"groupId\": \"com.example\"");
        assertThat(json).contains("\"groupId\": \"org.test\"");
        assertThat(json).contains("\"classifier\": \"sources\"");
    }

    @Test
    void flushCreatesParentDirectories() {
        RecordedArtifactStore.capture(artifact("com.example", "lib-a", "1.0", "", "jar", "https://repo/a"));
        File outputFile = tempDir.resolve("sub/dir/output.json").toFile();
        RecordedArtifactStore.flush(outputFile);
        assertThat(outputFile).exists();
    }

    @Test
    void flushEmptyStoreWritesEmptyArray() throws IOException {
        File outputFile = tempDir.resolve("empty.json").toFile();
        RecordedArtifactStore.flush(outputFile);
        String json = Files.readString(outputFile.toPath());
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).doesNotContain("\"groupId\"");
    }

    @Test
    void isRecordableExtension() {
        assertThat(RecordedArtifactStore.isRecordableExtension("jar")).isTrue();
        assertThat(RecordedArtifactStore.isRecordableExtension("pom")).isTrue();
        assertThat(RecordedArtifactStore.isRecordableExtension("war")).isTrue();
        assertThat(RecordedArtifactStore.isRecordableExtension("sha1")).isFalse();
        assertThat(RecordedArtifactStore.isRecordableExtension("asc")).isFalse();
    }

    private static RecordedArtifact artifact(
            String groupId, String artifactId, String version, String classifier, String extension, String url) {
        return new RecordedArtifact(groupId, artifactId, version, classifier, extension, url);
    }
}
