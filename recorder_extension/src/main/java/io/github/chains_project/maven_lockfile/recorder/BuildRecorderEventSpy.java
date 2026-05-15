package io.github.chains_project.maven_lockfile.recorder;

import java.io.File;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;

@Named("maven-lockfile-recorder-spy")
@Singleton
public class BuildRecorderEventSpy extends AbstractEventSpy {

    @Override
    public void onEvent(Object event) {
        if (!(event instanceof ExecutionEvent)) return;
        ExecutionEvent execEvent = (ExecutionEvent) event;
        if (execEvent.getType() != ExecutionEvent.Type.MojoStarted) return;

        String artifactId = execEvent.getMojoExecution().getPlugin().getArtifactId();
        String goal = execEvent.getMojoExecution().getGoal();

        if (!"maven-lockfile".equals(artifactId)) return;
        if (!"generate".equals(goal) && !"validate".equals(goal)) return;

        File outputFile = BuildRecorderLifecycleParticipant.getOutputFile(execEvent.getSession());
        RecordedArtifactStore.flush(outputFile);
        System.out.println("[maven-lockfile-recorder] Pre-flush " + RecordedArtifactStore.size()
                + " artifact(s) before lockfile:" + goal);
    }
}
