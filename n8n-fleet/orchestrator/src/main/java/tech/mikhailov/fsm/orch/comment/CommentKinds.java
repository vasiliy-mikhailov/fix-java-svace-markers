package tech.mikhailov.fsm.orch.comment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tech.mikhailov.fsm.feedback.Critique;
import tech.mikhailov.fsm.feedback.CritiqueKind;

/**
 * THE TWO CLOSED VOCABULARIES A HUMAN COMMENT MAY USE, and the one rule about each.
 *
 * <h2>STAGE IS CLOSED. KIND IS NOT.</h2>
 *
 * <p>{@link #STAGES} is the five stages exactly as {@link Critique} spells them, and a comment that
 * names anything else is REFUSED. The stage is not decoration: it is the answer to "whose prompt would
 * have to change", it is what {@code CritiqueIndex} already groups the machine's complaints by, and
 * {@code prompts/&lt;stage&gt;.txt} is derived from it. A free-text stage would produce a comment filed
 * against {@code Reproducer}, {@code repro} and {@code reproducer} as three different things — which is
 * not a smaller feature than refusing it, it is a silently broken one.
 *
 * <p>{@link #KNOWN} is {@link CritiqueKind}'s slugs, and a comment may use one of them, a slug of its
 * own, or none at all. That asymmetry is deliberate and it is the harder of the two calls:
 *
 * <ul>
 *   <li>REFUSING AN UNKNOWN KIND WOULD BE REFUSING THE COMMENT. {@code CritiqueKind}'s own rule is that
 *       a kind is added there only when code can decide it from evidence. A person's complaint is
 *       routinely about something no code detects — that is the entire reason this channel exists
 *       beside the harvester — so the vocabulary that is right for the machine cannot be a gate on the
 *       human.</li>
 *   <li>BUT A KIND THAT MATCHES COUNTS TOGETHER, which is the point of offering it at all. A person
 *       who picks {@code excessive_mocking} is adding to the same total the realness scorer is filling,
 *       and the two are then countable in one query.</li>
 *   <li>SO THE ANSWER SAYS WHICH IT WAS. Every stored comment carries {@code kind_known}, and the form
 *       is expected to offer {@link #KNOWN} as a list with free entry beside it. A typo splitting
 *       {@code excessive_mocking} in two is then visible in the response rather than discovered a
 *       month later in a count that is quietly half of what it should be.</li>
 * </ul>
 *
 * <p>{@link #KNOWN} IS READ OFF {@link CritiqueKind} BY REFLECTION rather than retyped. A hand-copied
 * list is a second declaration of the vocabulary, and the failure mode of a second declaration is that
 * a kind added to the machine's list never appears in the human's — which is exactly the drift that
 * makes the two channels stop being countable together. {@code CommentKindsTest} pins that the list is
 * non-empty and that it holds the kinds the panels already show.
 */
public final class CommentKinds {

    /** The longest slug accepted. Long enough for every kind in {@link CritiqueKind} and then some. */
    public static final int KIND_MAX = 40;

    /**
     * The five stages, exactly as {@link Critique} spells them, plus {@code ""} for the marker as a
     * whole. Ordered as the pipeline runs, because that is the order the modal's tabs are in.
     */
    public static final List<String> STAGES = List.of(Critique.REPRODUCER, Critique.FIXER,
            Critique.FIX_SKEPTIC, Critique.PR_MAKER, Critique.VERDICT);

    /** Every slug {@link CritiqueKind} declares, in declaration order. @see CommentKinds */
    public static final List<String> KNOWN = readKinds();

    private CommentKinds() {
    }

    /**
     * Is this one of the machine's own kinds — i.e. will it count together with the harvested
     * critiques? Never a reason to refuse; only ever a fact reported back.
     */
    public static boolean known(String kind) {
        return kind != null && KNOWN.contains(kind);
    }

    /** Is this a stage the pipeline has? {@code ""} means the marker as a whole and is always valid. */
    public static boolean validStage(String stage) {
        return stage == null || stage.isEmpty() || STAGES.contains(stage);
    }

    /**
     * A human's typing, as a slug — or {@code null} when it cannot be one.
     *
     * <p>Trimmed, lowercased, spaces and hyphens folded to underscores, so that someone who types
     * "Excessive Mocking" into a free-text box lands on the same slug the realness scorer writes
     * instead of on a second bucket beside it. Anything left over after that — punctuation, a class
     * name, a sentence — is NOT quietly stripped: it is refused, and the free text is where it
     * belongs. A "kind" that is a sentence is a kind nothing can group by.
     *
     * @return the normalised slug, {@code ""} for absent, or {@code null} when the input cannot be a
     *         kind at all and the request must be refused
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (slug.isEmpty()) {
            return "";
        }
        if (slug.length() > KIND_MAX) {
            return null;
        }
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return null;
            }
        }
        return slug;
    }

    /**
     * {@link CritiqueKind}'s public constants, by reflection. @see CommentKinds
     *
     * <p>It reads {@code public static final String} fields and nothing else, so a helper method or a
     * private constant added to that class cannot leak into the vocabulary offered to a person.
     */
    private static List<String> readKinds() {
        List<String> out = new ArrayList<>();
        for (Field field : CritiqueKind.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                try {
                    Object value = field.get(null);
                    if (value instanceof String slug && !slug.isBlank()) {
                        out.add(slug);
                    }
                } catch (IllegalAccessException e) {
                    // A public constant on a public class cannot be inaccessible; if the module system
                    // ever makes it so, an incomplete list is better than a dead application context.
                    // The absence is visible: CommentKindsTest asserts the kinds the panels show.
                    continue;
                }
            }
        }
        return List.copyOf(out);
    }
}
