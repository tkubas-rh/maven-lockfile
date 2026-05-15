package io.github.chains_project.maven_lockfile.recorder;

public final class RecordedArtifact {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String classifier;
    private final String extension;
    private final String url;

    RecordedArtifact(
            String groupId, String artifactId, String version, String classifier, String extension, String url) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
        this.extension = extension;
        this.url = url;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getExtension() {
        return extension;
    }

    public String getUrl() {
        return url;
    }
}
