package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Marker;

/**
 * THE INPUT BOUNDARY of {@link ProveMarker}: the marker this run is holding.
 *
 * <p>A record with one component rather than a bare argument, because the boundary is where the next
 * thing this use case needs will arrive — a run id, a deadline — and adding a component is a compile
 * error at every driver, where changing a parameter list quietly is not.
 */
public record ProveMarkerRequest(Marker marker) {

    public ProveMarkerRequest {
        if (marker == null) {
            throw new IllegalArgumentException("proving needs a claimed marker");
        }
    }
}
