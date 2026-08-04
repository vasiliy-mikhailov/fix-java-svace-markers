package tech.mikhailov.fsm.orch.dao;

import tech.mikhailov.fsm.orch.domain.Artifact;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.usecase.ArtifactRepository;

/**
 * {@code ArtifactRepository} over the {@code bugs} table, keeping the {@code MERGE … KEY
 * (suspicion_key)} upsert and every clipped column exactly where they are.
 *
 * <p>THE ONE DOWNCAST IN THE SLICE, AND WHERE ITS COST IS PAID. {@link Artifact} declares one method,
 * because one method is all the prove policy reads off an artifact; the 22 columns live on {@link Bug},
 * outside the domain, so that they are declared once in this process and not twice. An adapter that
 * must persist all 22 therefore has to come back down to the concrete type. The alternative — an
 * {@code Artifact} that exposes its columns — would put the schema back inside the innermost circle,
 * which is the thing the interface exists to prevent.
 */
public final class JdbcArtifactRepository implements ArtifactRepository {

    private final BugDao bugs;

    public JdbcArtifactRepository(BugDao bugs) {
        this.bugs = bugs;
    }

    @Override
    public void store(Artifact artifact) {
        if (!(artifact instanceof Bug bug)) {
            throw new IllegalArgumentException("this process writes artifacts to the `bugs` table and "
                    + "has one shape for them; " + (artifact == null ? "null"
                    : artifact.getClass().getName()) + " is not it");
        }
        bugs.upsert(bug);
    }
}
