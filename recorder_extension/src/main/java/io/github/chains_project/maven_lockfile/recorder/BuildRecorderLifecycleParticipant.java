package io.github.chains_project.maven_lockfile.recorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

@Named("maven-lockfile-recorder")
@Singleton
public class BuildRecorderLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final String OUTPUT_FILENAME = ".mvn/build-recorded-artifacts.json";

    private File outputFile;

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        RecordedArtifactStore.reset();

        outputFile = new File(session.getRequest().getMultiModuleProjectDirectory(), OUTPUT_FILENAME);

        List<RemoteRepository> remoteRepos = buildRemoteRepos(session);
        @SuppressWarnings("deprecation")
        String localRepoBase = session.getLocalRepository().getBasedir();

        RepositorySystemSession originalSession = session.getRepositorySession();
        DefaultRepositorySystemSession mutableSession = new DefaultRepositorySystemSession(originalSession);
        replaceRepositorySession(session, mutableSession);

        RepositoryListener existingListener = mutableSession.getRepositoryListener();

        mutableSession.setRepositoryListener(new ChainedRepositoryListener(existingListener) {
            @Override
            public void artifactResolved(RepositoryEvent event) {
                super.artifactResolved(event);
                if (event.getException() != null) return;
                Artifact artifact = event.getArtifact();
                if (!RecordedArtifactStore.isRecordableExtension(artifact.getExtension())) return;

                RemoteRepository sourceRepo = null;
                org.eclipse.aether.repository.ArtifactRepository repo = event.getRepository();
                if (repo instanceof RemoteRepository) {
                    sourceRepo = (RemoteRepository) repo;
                } else if (repo instanceof LocalRepository && event.getFile() != null) {
                    sourceRepo = findOriginRepo(event.getFile(), remoteRepos);
                }

                if (sourceRepo == null) return;

                String base = sourceRepo.getUrl().endsWith("/") ? sourceRepo.getUrl() : sourceRepo.getUrl() + "/";
                String urlPath = "";
                if (event.getFile() != null) {
                    String absPath = event.getFile().getAbsolutePath();
                    String localBase =
                            localRepoBase.endsWith(File.separator) ? localRepoBase : localRepoBase + File.separator;
                    if (absPath.startsWith(localBase)) {
                        urlPath = absPath.substring(localBase.length()).replace(File.separatorChar, '/');
                    }
                }

                RecordedArtifactStore.capture(new RecordedArtifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion(),
                        artifact.getClassifier(),
                        artifact.getExtension(),
                        base + urlPath));
            }
        });

        System.out.println("[maven-lockfile-recorder] Recording listener attached — "
                + "all artifact resolutions will be captured");
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        if (outputFile == null) return;
        RecordedArtifactStore.flush(outputFile);
        int count = RecordedArtifactStore.size();
        System.out.println("[maven-lockfile-recorder] Flushed " + count + " artifact(s) to " + outputFile);
        RecordedArtifactStore.reset();
    }

    static File getOutputFile(MavenSession session) {
        return new File(session.getRequest().getMultiModuleProjectDirectory(), OUTPUT_FILENAME);
    }

    private static List<RemoteRepository> buildRemoteRepos(MavenSession session) {
        List<RemoteRepository> repos = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (MavenProject project : session.getProjects()) {
            for (ArtifactRepository repo : project.getRemoteArtifactRepositories()) {
                if (seenIds.add(repo.getId())) {
                    repos.add(new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl()).build());
                }
            }
            for (ArtifactRepository repo : project.getPluginArtifactRepositories()) {
                if (seenIds.add(repo.getId())) {
                    repos.add(new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl()).build());
                }
            }
        }
        return repos;
    }

    private static void replaceRepositorySession(MavenSession session, RepositorySystemSession newSession) {
        try {
            Field field = MavenSession.class.getDeclaredField("repositorySession");
            field.setAccessible(true);
            field.set(session, newSession);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[maven-lockfile-recorder] Failed to replace repository session", e);
        }
    }

    private static RemoteRepository findOriginRepo(File artifactFile, List<RemoteRepository> remoteRepos) {
        File trackingFile = new File(artifactFile.getParentFile(), "_remote.repositories");
        if (!trackingFile.exists()) return null;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(trackingFile)) {
                props.load(fis);
            }
            String prefix = artifactFile.getName() + ">";
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    String repoId = key.substring(prefix.length());
                    for (RemoteRepository repo : remoteRepos) {
                        if (repo.getId().equals(repoId)) return repo;
                    }
                }
            }
        } catch (IOException e) {
            // ignore — warm-cache lookup is best-effort
        }
        return null;
    }
}
