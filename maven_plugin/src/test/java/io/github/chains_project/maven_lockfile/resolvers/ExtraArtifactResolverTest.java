package io.github.chains_project.maven_lockfile.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExtraArtifactResolver} — the parts that can be exercised
 * without a live Maven session or Aether repository.
 */
class ExtraArtifactResolverTest {

    // -------------------------------------------------------------------------
    // extractExtras — null-safety and deduplication contract
    // -------------------------------------------------------------------------

    @Test
    void extractExtras_returnsEmptyListWhenTrackerIsNull() {
        // Contract: null tracker (e.g. when ExtraArtifactResolver is skipped) must not throw.
        var result = ExtraArtifactResolver.extractExtras(null, Set.of(), null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void extractExtras_returnsEmptyListWhenTrackerIsNullRegardlessOfAlreadyRecorded() {
        // The alreadyRecorded set does not matter when tracker is null.
        var recorded =
                Set.of("com.google.protobuf:protoc:3.25.5:exe", "org.junit.jupiter:junit-jupiter-api:5.10.2:jar");

        var result = ExtraArtifactResolver.extractExtras(null, recorded, null);

        assertThat(result).isEmpty();
    }
}
