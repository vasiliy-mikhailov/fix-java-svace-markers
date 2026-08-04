package tech.mikhailov.fsm.orch.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The marker as the ENGINE reads it — the 23 wire columns, in table order, that
 * {@code PrepProver.Request} was written against.
 *
 * <p>WHY THE ENTITY CARRIES A MAP IT NEVER LOOKS INSIDE. The engine is an external service to this
 * layer: four differential harnesses pin 6,910 engine cases through its public entry points and the
 * catalogues cannot be regenerated, so its input shape is fixed and is not ours to re-model. A snapshot
 * is therefore the marker AS THAT SERVICE SEES IT — one value, passed through, never interpreted here.
 * Re-typing its 23 keys in the domain would create a third column list to keep in step by hand and
 * would buy nothing, because no rule in this package branches on any of them.
 *
 * <p>NUMBERS STAY NUMBERS. {@code PrepProver} reads {@code svace_line} through {@code Json.num} and
 * {@code prove_attempts} through {@code JsValue.numberOrZero}; handing either over as a string works by
 * coercion today and changes meaning the moment one of them is blank. Nulls stay present as nulls, for
 * the reason {@code Suspicion.toMap} states: an absent key and a key holding null are different values
 * to the engine, and a column read back from SQL is the second one.
 */
public record MarkerSnapshot(Map<String, Object> fields) {

    public MarkerSnapshot {
        if (fields == null) {
            throw new IllegalArgumentException("a marker snapshot is what the engine is handed; an "
                    + "absent one is a prove with no marker in it");
        }
        // COPIED, and insertion-ordered, so the row the entity was built from cannot be edited through
        // the snapshot afterwards. NOT frozen: the engine is handed this map as a stage request and the
        // catalogue-pinned stages are not ours to constrain, so it gets exactly what
        // `Suspicion.toMap()` handed them before — one fresh, writable LinkedHashMap per prove.
        fields = new LinkedHashMap<>(fields);
    }
}
