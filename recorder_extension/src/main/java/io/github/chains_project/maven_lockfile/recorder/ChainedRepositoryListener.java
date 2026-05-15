package io.github.chains_project.maven_lockfile.recorder;

import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;

abstract class ChainedRepositoryListener extends AbstractRepositoryListener {

    private final RepositoryListener delegate;

    ChainedRepositoryListener(RepositoryListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void artifactDeployed(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDeployed(e);
    }

    @Override
    public void artifactDeploying(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDeploying(e);
    }

    @Override
    public void artifactDescriptorInvalid(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDescriptorInvalid(e);
    }

    @Override
    public void artifactDescriptorMissing(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDescriptorMissing(e);
    }

    @Override
    public void artifactDownloaded(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDownloaded(e);
    }

    @Override
    public void artifactDownloading(RepositoryEvent e) {
        if (delegate != null) delegate.artifactDownloading(e);
    }

    @Override
    public void artifactInstalled(RepositoryEvent e) {
        if (delegate != null) delegate.artifactInstalled(e);
    }

    @Override
    public void artifactInstalling(RepositoryEvent e) {
        if (delegate != null) delegate.artifactInstalling(e);
    }

    @Override
    public void artifactResolved(RepositoryEvent e) {
        if (delegate != null) delegate.artifactResolved(e);
    }

    @Override
    public void artifactResolving(RepositoryEvent e) {
        if (delegate != null) delegate.artifactResolving(e);
    }

    @Override
    public void metadataDeployed(RepositoryEvent e) {
        if (delegate != null) delegate.metadataDeployed(e);
    }

    @Override
    public void metadataDeploying(RepositoryEvent e) {
        if (delegate != null) delegate.metadataDeploying(e);
    }

    @Override
    public void metadataDownloaded(RepositoryEvent e) {
        if (delegate != null) delegate.metadataDownloaded(e);
    }

    @Override
    public void metadataDownloading(RepositoryEvent e) {
        if (delegate != null) delegate.metadataDownloading(e);
    }

    @Override
    public void metadataInstalled(RepositoryEvent e) {
        if (delegate != null) delegate.metadataInstalled(e);
    }

    @Override
    public void metadataInstalling(RepositoryEvent e) {
        if (delegate != null) delegate.metadataInstalling(e);
    }

    @Override
    public void metadataInvalid(RepositoryEvent e) {
        if (delegate != null) delegate.metadataInvalid(e);
    }

    @Override
    public void metadataResolved(RepositoryEvent e) {
        if (delegate != null) delegate.metadataResolved(e);
    }

    @Override
    public void metadataResolving(RepositoryEvent e) {
        if (delegate != null) delegate.metadataResolving(e);
    }
}
