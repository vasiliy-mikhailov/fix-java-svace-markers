package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A FRAMING THE AGENT CAN CHECK AND FIND FALSE IS A FRAMING IT DISCARDS.
 *
 * <p>The stakes preamble was first written as an assertion of present fact: "The code in front of
 * you RUNS on a production server reachable from the internet." The subject is WebGoat, which is
 * teaching code that is vulnerable on purpose — a fact the agent reads straight out of the lesson
 * documentation and a committed test asserting the attack succeeds. So the premise was falsifiable,
 * and false.
 *
 * <p>It then lost to the rest of its own paragraph, which says "support what you say from what is
 * actually in front of you" — and to the argue prompt, which explicitly authorises {@code by-design}
 * when an assignment "would stop being solvable". Six of the seven markers proved with that wording
 * still settled on the lesson argument. The instruction was delivered to every agent and overridden
 * by every one of them.
 *
 * <p>The request had been "act AS IF this will be deployed" — a counterfactual, which cannot be
 * contradicted by evidence because it does not claim anything about the world. Writing it as a claim
 * about the world was the whole of the mistake, and the cost is worse than the instruction simply
 * being absent: an agent that catches one sentence of its prompt lying has been given a reason to
 * weigh the rest of it less.
 */
class AFramingAnAgentCanFalsifyTest {

    @Test
    @DisplayName("the stakes are a standard to judge by, not a claim about the subject")
    void notAnAssertionOfFact() {
        String stakes = Agents.STAKES;
        // The failing wording, and anything shaped like it: a present-tense claim that this
        // particular code is deployed somewhere. The agent can open the repository and check.
        List<String> asserts = List.of(
                "code in front of you runs on a production",
                "this code runs on a production",
                "is deployed to a production",
                "is running in production");
        for (String claim : asserts) {
            assertTrue(!stakes.toLowerCase().contains(claim),
                    "the preamble states as fact something an agent can falsify by reading the "
                            + "subject, and a prompt caught lying is weighed less whole: \"" + claim + "\"");
        }
        assertTrue(stakes.contains("AS IF") || stakes.contains("as if"),
                "the framing has to be explicitly counterfactual — that is what makes it survive a "
                        + "subject which is deliberately vulnerable teaching code");
    }

    @Test
    @DisplayName("and it does not contradict the settlement the chain already offers")
    void doesNotForbidByDesign() {
        String stakes = Agents.STAKES.toLowerCase();
        // `by-design` is a real disposition with its own critic and its own evidence rules. A
        // preamble that implied it was never available would put every argue agent in a bind
        // between two parts of its own prompt — which is what the first wording did.
        assertTrue(stakes.contains("teaching code") || stakes.contains("on purpose"),
                "the preamble must acknowledge that a deliberately vulnerable subject is possible, "
                        + "or it contradicts the argue prompt that authorises `by-design`");
        assertTrue(stakes.contains("evidence") || stakes.contains("show"),
                "what the standard changes is the EVIDENCE required, not which words are available");
    }

    @Test
    @DisplayName("it reaches the agents that judge the subject, and no others")
    void goesWhereItBelongs() {
        for (String agent : Agents.CHAIN) {
            assertTrue(!Agents.staked(agent).isBlank(), agent + " judges the subject and must have it");
        }
        for (String agent : Agents.WATCH) {
            boolean interpreter = agent.startsWith("interpreter-");
            assertTrue(Agents.staked(agent).isBlank() != interpreter,
                    agent + ": the interpreter writes the account a person acts on and gets it; "
                            + "overwatch judges this pipeline rather than the subject, and telling "
                            + "it that its answer ships to production is simply false");
        }
    }
}
