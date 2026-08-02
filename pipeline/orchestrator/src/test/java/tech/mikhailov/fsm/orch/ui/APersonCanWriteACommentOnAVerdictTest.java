package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.orch.comment.CommentKinds;
import tech.mikhailov.fsm.orch.comment.MarkerComment;

/**
 * A PERSON WRITES A JUDGEMENT ABOUT AN ARTIFACT, ON THE ARTIFACT, THROUGH THE PAGE.
 *
 * <h2>WHAT THIS IS, AND WHAT IT IS NOT</h2>
 *
 * <p>The feedback store already records what the PIPELINE thinks: the realness scorer counts "9
 * stub/mock setup(s) for collaborators", files it under a stable kind, and the guidance panel groups
 * it. That is a machine's opinion of a machine's output, and it is not this.
 *
 * <p>This is the HUMAN channel beside it. The sentence the user asked to be able to write is "I don't
 * like too many mocks, this one and this one are redundant" — a judgement about a specific reproducer,
 * which no scorer produces and which is the only kind of feedback that can say WHICH two mocks. It is
 * written on the tab whose output is being criticised, so it arrives already attributed to the stage —
 * and therefore to the prompt — that would have to change.
 *
 * <h2>THE FOUR WAYS A FEATURE LIKE THIS GOES UNUSED</h2>
 *
 * <ol>
 *   <li>IT NEEDS A MOUSE. Everybody who writes a paragraph writes it from the keyboard; a box that can
 *       only be reached and submitted with a pointer is a box that gets used once.</li>
 *   <li>THE PAGE EATS THE TEXT. This dashboard re-renders itself every three seconds for hours. A
 *       refresh that empties a half-written comment costs the person their paragraph, and it costs it
 *       AGAIN the second time they try — after which the feature is dead.</li>
 *   <li>A FAILED POST LOOKS LIKE A SUCCESSFUL ONE. If the store is down and the box just clears, the
 *       person believes they have filed a judgement that exists nowhere. The text must stay in the box
 *       and the failure must be on the screen.</li>
 *   <li>NOBODY CAN FIND WHAT WAS WRITTEN. "Which previous markers have negative comments" was the
 *       question that could not be answered, and it is not answered by a box that shows its contents
 *       only to whoever already opened that exact marker.</li>
 * </ol>
 *
 * <p>Each of the four is a test below, and every one of them is about the BROWSER: in all four the
 * request either succeeds or is never made, so nothing on the server can see any of it.
 *
 * <p>AGAINST THE REAL ENDPOINT AND THE REAL STORE. {@code POST /api/comment} and
 * {@code GET /api/comment?key=} are served by {@code CommentController} over H2 and the durable
 * journal — every assertion below that reads the store reads what the page actually caused to be
 * written. The one exception is the refusal, which is injected at the transport because "the store is
 * down" is not a state a service can be asked to enter.
 */
@Tag("ui")
class APersonCanWriteACommentOnAVerdictTest extends DashboardUi {

    /** The user's own example of the comment this feature exists for. */
    private static final String THE_COMMENT =
            "I don't like too many mocks, this one and this one are redundant";

    private static final String AUTHOR = "vasiliy";

    /** Written on the Reproducer tab, so this is the stage the page must attribute it to. */
    private static final String REPRODUCER_STAGE = "reproducer";

    /** The tab the verdict itself is rendered on. @see #REPRODUCER_STAGE */
    private static final String VERDICT_STAGE = "verdict";

    // ---- 1. WRITING, FROM THE KEYBOARD ALONE -----------------------------------------------------

    /**
     * TYPED AND SUBMITTED WITHOUT A POINTER, AND WHAT WAS TYPED IS WHAT WAS STORED.
     *
     * <p>Not one mouse event after the modal opens: the tab is reached with Tab and activated with
     * Enter, the box is reached with Tab, the comment is typed, and Ctrl+Enter files it. That sequence
     * is the point — a reviewer reading a reproducer already has both hands on the keyboard.
     *
     * <p>AND THE ATTRIBUTION IS THE PAGE'S JOB, not the person's. The comment reaches the store already
     * carrying the marker it is about and the stage whose output it criticises; asking a reviewer to
     * pick those out of a dropdown is asking them to file it under the wrong one.
     */
    @Test
    void aCommentIsTypedAndFiledFromTheKeyboardAloneAndAppearsWithoutAReload() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        // A value set on the window survives every re-render this page does and NOTHING else. If it is
        // gone at the end, the page navigated — which is the "and then it reloaded and I lost my place
        // in a 282-row table" version of this feature.
        page.evaluate("() => { window.__noReload = 'still the same document'; }");

        tabTo("#mtabs .tab", "Reproducer");
        page.keyboard().press("Enter");
        page.locator("#cmt[data-stage=\"" + REPRODUCER_STAGE + "\"]").waitFor();
        awaitCommentBox();

        tabTo("#cmtauthor", "");
        page.keyboard().type(AUTHOR);
        // The optional kind, offered from the SERVER's list of the kinds the pipeline's own critiques
        // are filed under — the one field that makes a person's complaint count in the same total the
        // guidance panel groups. Typed rather than picked, because the control has to work either way.
        tabTo("#cmtkind", "");
        page.keyboard().type(CritiqueKind.EXCESSIVE_MOCKING);
        tabTo("#cmttext", "");
        page.keyboard().type(THE_COMMENT);

        // The submit that does not need the mouse. The button is there too and is proved reachable
        // below; this is the one somebody who is already typing will use.
        page.keyboard().press("Control+Enter");

        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");

        // WHAT THE STORE ACTUALLY HOLDS — the half a rendered list cannot show.
        List<MarkerComment> stored = storedComments(Seeds.PROVEN_1);
        assertThat(stored)
                .as("the page rendered a comment it never managed to file; the list on screen is then "
                        + "a lie that survives until the next reload")
                .hasSize(1);
        MarkerComment filed = stored.get(0);
        assertThat(filed.text()).isEqualTo(THE_COMMENT);
        assertThat(filed.author()).isEqualTo(AUTHOR);
        assertThat(filed.stage())
                .as("a complaint about redundant mocks is about the REPRODUCER's prompt, and it was "
                        + "written on the reproducer's tab — the page attributes it, the person does not")
                .isEqualTo(REPRODUCER_STAGE);
        assertThat(filed.dedupKey())
                .as("filed against the marker whose modal was open, by the same dedup_key /api/bug and "
                        + "/api/feedback/marker are addressed with")
                .isEqualTo(Seeds.PROVEN_1);
        assertThat(filed.kind()).isEqualTo(CritiqueKind.EXCESSIVE_MOCKING);
        assertThat(filed.kindKnown())
                .as("the comment landed under a kind the pipeline does not recognise, so it counts "
                        + "towards nothing — a misspelling here splits a total in two and the split "
                        + "is only ever noticed as a number that is quietly half of what it should be")
                .isTrue();

        // AND THE LIST CAME OFF THE WIRE. A hardcoded copy in app.js would pass every assertion above
        // and go stale the first time a kind is added to CritiqueKind.
        assertThat(page.locator("#cmtkinds option").count())
                .as("the kind field offers nothing, so the only kinds a person can file under are the "
                        + "ones they happen to remember the spelling of")
                .isEqualTo(CommentKinds.KNOWN.size());

        // AND IT IS ON THE SCREEN, in the same document, without anybody asking for it again.
        assertThat(commentsOnScreen()).singleElement().asString()
                .contains(THE_COMMENT)
                .contains(AUTHOR);
        assertThat(page.evaluate("() => window.__noReload"))
                .as("the page reloaded to show the comment it had just posted")
                .isEqualTo("still the same document");
        assertThat(typedComment())
                .as("a comment that was filed must leave the box, or the next reader files it twice")
                .isEmpty();

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * THE BUTTON IS A BUTTON: reachable by Tab and activated by Enter.
     *
     * <p>Ctrl+Enter is a shortcut, and a shortcut is not an interface. Somebody who does not know it —
     * everybody, the first time — tabs to the control that says what it does and presses Enter, and
     * that has to work or the test above is proving a feature only its author can use.
     */
    @Test
    void theSubmitControlIsReachableByTabAndActivatedByEnter() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();

        String aboutTheVerdict = "the argument is sound but it quotes evidence from the wrong line";
        tabTo("#cmttext", "");
        page.keyboard().type(aboutTheVerdict);
        tabTo("#cmtpost", "");
        page.keyboard().press("Enter");

        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");
        List<MarkerComment> stored = storedComments(Seeds.PROVEN_1);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).text()).isEqualTo(aboutTheVerdict);
        assertThat(stored.get(0).stage())
                .as("the marker tab is where the verdict is rendered, so a comment written there is "
                        + "about the verdict — not about the reproducer whose tab is next to it")
                .isEqualTo(VERDICT_STAGE);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    // ---- 2. THE POLL MUST NOT EAT IT -------------------------------------------------------------

    /**
     * THE PAGE REFRESHES ITSELF UNDER THE PERSON'S HANDS AND THE HALF-WRITTEN COMMENT SURVIVES.
     *
     * <p>THE FALLBACK POLL IS THE REAL ONE. {@code liveLoop} fires every three seconds and calls
     * {@code tick()} whenever the socket is down or has gone quiet, and the socket being down is not a
     * hypothesis: it is what every reader behind a proxy that will not forward an Upgrade gets, all
     * day. So the socket is declared down here and the page's OWN three-second interval is left to do
     * the work — twice, because a draft that survives one repaint and not the second is a draft that
     * dies at 3.001 seconds.
     *
     * <p>THE CARET MATTERS AS MUCH AS THE TEXT. A repaint that preserved the value but stole the focus
     * would move the cursor out of the box mid-sentence, and the rest of the paragraph would go
     * nowhere at all.
     */
    @Test
    void thePollDoesNotClearAHalfWrittenComment() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();

        String halfWritten = "I don't like too many mocks, this one and this";
        tabTo("#cmttext", "");
        page.keyboard().type(halfWritten);

        page.evaluate("""
                () => {
                  // Count the page's own repaints, so this test can prove it waited THROUGH some
                  // rather than merely waiting. render() is what redraws every table on the page.
                  window.__renders = 0;
                  const original = render;
                  render = function (state) { window.__renders++; return original(state); };
                  // The socket declared down is what puts liveLoop back on the poll — the state a
                  // reader behind a proxy that will not forward an Upgrade is in permanently.
                  LIVE.connected = false;
                  LIVE.last = 0;
                }""");
        page.waitForFunction("() => window.__renders >= 2", null,
                new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(20_000));

        assertThat(typedComment())
                .as("the page repainted itself %s times while somebody was typing and took the "
                        + "half-written comment with it. Nothing errors and nothing is logged: the "
                        + "person simply watches their paragraph disappear",
                        page.evaluate("() => window.__renders"))
                .isEqualTo(halfWritten);
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id"))
                .as("the box kept its text but lost the caret, so the rest of the sentence goes nowhere")
                .isEqualTo("cmttext");

        // …and it is still a comment that can be finished and filed.
        page.keyboard().type(" one are redundant");
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");
        assertThat(storedComments(Seeds.PROVEN_1).get(0).text()).isEqualTo(THE_COMMENT);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * SWITCHING TABS TO CHECK THE THING YOU ARE COMPLAINING ABOUT DOES NOT COST YOU THE COMPLAINT.
     *
     * <p>The modal's body is thrown away and rebuilt on every tab switch — that is how it has always
     * worked — and the person most likely to be half-way through a comment about a reproducer is the
     * person who wants one more look at the fix before they finish the sentence. Two drafts on two tabs
     * are also kept apart, because a draft that followed the reader from tab to tab would file a
     * complaint about the reproducer against the fixer.
     */
    @Test
    void aDraftSurvivesLookingAtAnotherTabAndDoesNotFollowTheReaderToIt() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        openTab("Reproducer");
        awaitCommentBox();
        tabTo("#cmttext", "");
        page.keyboard().type(THE_COMMENT);

        openTab("Fixer");
        awaitCommentBox();
        assertThat(typedComment())
                .as("the draft about the reproducer followed the reader onto the fixer's tab, where "
                        + "posting it would attribute it to the wrong stage and the wrong prompt")
                .isEmpty();

        openTab("Reproducer");
        awaitCommentBox();
        assertThat(typedComment())
                .as("the paragraph was thrown away by a tab switch — the reader looked at the fix "
                        + "they were about to mention and lost the sentence mentioning it")
                .isEqualTo(THE_COMMENT);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    // ---- 3. A FAILED POST IS VISIBLE AND COSTS NOTHING -------------------------------------------

    /**
     * THE STORE REFUSES THE WRITE, THE PERSON IS TOLD, AND NOT ONE CHARACTER IS LOST.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the silent version of this — clear the box, render
     * nothing, log nothing — tells somebody they have filed a judgement that exists nowhere. They find
     * out weeks later, if ever, and the paragraph is gone either way. A comment box that can lose text
     * is worse than no comment box, because it is trusted.
     *
     * <p>{@link #assertEveryRequestAnswered()} is deliberately NOT called: the failing POST is a 503
     * this test arranged on purpose, and that check exists to find the failures nobody arranged.
     */
    @Test
    void aRefusedPostSaysSoAndKeepsEveryCharacterThatWasTyped() {
        String reason = "the comment store is read-only on this deployment";
        refuseTheNextCommentPost(503, "journal_unwritable", reason);

        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();

        tabTo("#cmttext", "");
        page.keyboard().type(THE_COMMENT);
        page.keyboard().press("Control+Enter");

        page.locator("#cmterr:visible").waitFor();
        String shown = page.locator("#cmterr").innerText();
        assertThat(shown)
                .as("the failure has to name itself; 'something went wrong' sends the reader to a log "
                        + "they do not have")
                .contains(reason);
        assertThat(shown)
                .as("and it has to say that nothing was recorded, or the reader assumes the opposite "
                        + "of whatever they cannot see")
                .containsIgnoringCase("not posted");

        assertThat(typedComment())
                .as("THE TEXT IS GONE. Everything else about this failure is recoverable and this is "
                        + "not: the person retypes the paragraph, or — far more likely — does not")
                .isEqualTo(THE_COMMENT);
        assertThat(storedComments(Seeds.PROVEN_1))
                .as("the write was refused, so there is nothing for the store to be holding")
                .isEmpty();
        assertThat(commentsOnScreen())
                .as("a comment that was refused must never be drawn as though it had been filed")
                .isEmpty();

        // AND IT RECOVERS. The next attempt goes to the real endpoint, which is what a person does
        // next — and it is the same text, still in the box, not typed again.
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");
        assertThat(storedComments(Seeds.PROVEN_1)).hasSize(1);
        assertThat(storedComments(Seeds.PROVEN_1).get(0).text()).isEqualTo(THE_COMMENT);
        assertThat(page.locator("#cmterr").isVisible())
                .as("the error from the attempt before is still on screen under a comment that was "
                        + "filed successfully")
                .isFalse();

        assertNoBrowserErrorsBeyondTheRefusedComment();
    }

    /**
     * A COMMENT THE SERVER REFUSES ON ITS MERITS IS REPORTED IN THE SERVER'S OWN WORDS.
     *
     * <p>No transport trickery here: this is the REAL endpoint refusing a real request, because the
     * marker was re-ingested away and the key no longer names anything. {@code CommentController}
     * answers those with a slug and a sentence; the page has to show the sentence rather than the slug,
     * and must not lose the text over it either.
     */
    @Test
    void aRefusalFromTheRealEndpointIsShownInTheServersOwnWords() {
        openDashboard();
        // A marker that is NOT in the backlog — the state the guidance panel's chips can reach, where
        // the store's accumulated history outlives the run that produced it.
        page.evaluate("""
                () => showInvestigation({dedup_key: 'ui-not-in-the-backlog',
                  file: 'src/main/java/tech/example/Vanished.java', svace_line: 7,
                  svace_checker: 'HANDLE_LEAK.EX', status: ''})""");
        page.locator("#modalbg.on").waitFor();
        awaitCommentBox();

        tabTo("#cmttext", "");
        page.keyboard().type("this marker went away with the last re-ingest");
        page.keyboard().press("Control+Enter");

        page.locator("#cmterr:visible").waitFor();
        String shown = page.locator("#cmterr").innerText();
        // THE SENTENCE, AND THE ASSERTION HAS TO BE ABLE TO TELL. `containsIgnoringCase("marker")`
        // was here and could not fail the case it was written for: the slug this test exists to
        // reject is `unknown_marker`, which contains the word "marker", so a page showing nothing but
        // the machine value would have passed. So it is checked against wording only the SENTENCE
        // has, and against the absence of the slug.
        assertThat(shown)
                .as("the reader is shown the machine slug `unknown_marker` and nothing else, which "
                        + "tells them neither what happened nor what to do about it")
                .contains("Nothing was stored")
                .containsIgnoringCase("backlog")
                .doesNotContain(tech.mikhailov.fsm.orch.comment.CommentService.ERR_UNKNOWN_MARKER);
        assertThat(typedComment()).isEqualTo("this marker went away with the last re-ingest");
        assertThat(storedComments("ui-not-in-the-backlog"))
                .as("a comment about a marker the store does not know was written anyway")
                .isEmpty();

        assertNoBrowserErrorsBeyondTheRefusedComment();
    }

    // ---- 4. WHAT IS ALREADY THERE, AND WHERE IT IS VISIBLE FROM -----------------------------------

    /**
     * THE COMMENTS ALREADY ON A MARKER, NEWEST FIRST, EACH WITH ITS STAGE, ITS AUTHOR AND ITS TIME.
     *
     * <p>Three attributes, all three load-bearing. The STAGE says which prompt the complaint is about,
     * and two people can disagree about the reproducer and the fix on the same marker. The AUTHOR is
     * who to go and ask. The TIME is what separates a judgement about the artifact in front of you from
     * one about the version that has since been re-proved.
     */
    @Test
    void theCommentsAlreadyOnAMarkerAreShownNewestFirstWithStageAuthorAndTime() {
        writeComment(Seeds.PROVEN_1, VERDICT_STAGE, "anna",
                "the rebuttal is right, but it argues against the wrong checker");
        writeComment(Seeds.PROVEN_1, REPRODUCER_STAGE, AUTHOR, THE_COMMENT);
        // Another marker's comment, which must not appear on this one — the modal-reuse defect this
        // suite has already caught twice, once with the artifact tabs and once with the critiques.
        writeComment(Seeds.SKIPPED_WITH_TEXT, "fixer", "kolya", "this fix is a revert in disguise");

        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();

        List<String> shown = commentsOnScreen();
        assertThat(shown).hasSize(2);
        assertThat(shown.get(0))
                .as("newest first: the most recent judgement about an artifact is the one that has "
                        + "seen the most of it")
                .contains(THE_COMMENT)
                .contains(AUTHOR)
                .contains(REPRODUCER_STAGE);
        assertThat(shown.get(1)).contains("anna").contains(VERDICT_STAGE);
        assertThat(String.join("\n", shown))
                .as("this marker is showing a comment somebody wrote about a different marker")
                .doesNotContain("revert in disguise");

        // THE TIME IS RENDERED, not merely carried. A comment with no date on it is one a reader
        // cannot place against the re-proves of the marker it is about.
        assertThat(page.locator("#cmtlist .cmt-one").first().locator(".cmt-when").innerText())
                .contains(String.valueOf(java.time.Year.now().getValue()));

        // AND THE SAME LIST IS ON THE OTHER TABS. A comment about the reproducer is written on the
        // reproducer's tab, and a reader who opened the Fixer tab must still be able to see it exists.
        openTab("Fixer");
        awaitCommentBox();
        assertThat(commentsOnScreen())
                .as("the comments on a marker are the MARKER's, not the tab's — only the box's own "
                        + "attribution follows the tab")
                .hasSize(2);
        assertThat(page.locator("#cmt").getAttribute("data-stage")).isEqualTo("fixer");

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * A MARKER THAT CARRIES HUMAN COMMENTS IS MARKED IN THE TABLES.
     *
     * <p>THIS IS THE QUESTION THAT COULD NOT BE ANSWERED: "which previous markers have negative
     * comments". Answering it by opening 282 modals in turn is not answering it. The mark is on the
     * row, so the answer is a glance down a column — and it is on BOTH tables, because the verdicts
     * panel is where a reviewer reads conclusions and the markers panel is where they read the backlog.
     *
     * <p>It is a COUNT and not a dot, because two people disagreeing about one marker is the row worth
     * opening first.
     */
    @Test
    void aMarkerCarryingCommentsIsMarkedInTheMarkersAndVerdictsTables() {
        writeComment(Seeds.PROVEN_1, REPRODUCER_STAGE, AUTHOR, THE_COMMENT);
        writeComment(Seeds.PROVEN_1, VERDICT_STAGE, "anna", "and the verdict overstates it");
        writeComment(Seeds.SKIPPED_WITH_TEXT, "fixer", "kolya", "this fix is a revert in disguise");

        openDashboard();
        page.waitForFunction("() => document.querySelectorAll('#suspicions .cmt-mark').length > 0");

        assertThat(markerRow(Seeds.PROVEN_1_FILE).locator(".cmt-mark").innerText())
                .as("the marker two people have written about is indistinguishable from the 280 "
                        + "nobody has")
                .contains("2");
        assertThat(markerRow(Seeds.SKIPPED_WITH_TEXT_FILE).locator(".cmt-mark").innerText())
                .contains("1");
        assertThat(page.locator("#suspicions tbody tr .cmt-mark").count())
                .as("exactly the two markers that carry comments are marked, and no others")
                .isEqualTo(2);
        assertThat(markerRow(Seeds.NO_ARTIFACT_FILE).locator(".cmt-mark").count())
                .as("a marker nobody has commented on must not be marked; a mark on every row marks "
                        + "nothing")
                .isZero();

        // THE VERDICTS TABLE TOO — that is the panel somebody is reading when they go looking.
        assertThat(page.locator("#verdicts tbody tr")
                        .filter(new com.microsoft.playwright.Locator.FilterOptions()
                                .setHasText(Seeds.PROVEN_1_FILE))
                        .locator(".cmt-mark").innerText())
                .contains("2");

        // AND THE MARK APPEARS THE MOMENT ONE IS WRITTEN, without a reload: the person who files the
        // comment is the one who most needs to see that it landed.
        openMarker(Seeds.NO_ARTIFACT_FILE);
        awaitCommentBox();
        tabTo("#cmttext", "");
        page.keyboard().type("not unprovable — the harness just cannot see the generated accessor");
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");
        closeModal();

        page.waitForFunction("() => document.querySelectorAll('#suspicions .cmt-mark').length === 3");
        assertThat(markerRow(Seeds.NO_ARTIFACT_FILE).locator(".cmt-mark").innerText()).contains("1");

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * THE SECOND MARKER SOMEBODY OPENS IS THE ONE THEIR COMMENT IS ABOUT.
     *
     * <p>ONE MODAL SERVES ALL 282 MARKERS. It is reused, not rebuilt: the same overlay, the same tab
     * strip, the same element ids, refilled every time a row is clicked. This suite has already caught
     * that reuse twice — once with the artifact tabs and once with the harvested critiques — and both
     * times the symptom was the previous marker's content under the current marker's heading.
     *
     * <p>HERE IT WOULD BE WORSE THAN A STALE PANEL, because this modal WRITES. A key left over from the
     * marker before would file somebody's judgement against a marker they never looked at: the sentence
     * is stored, no error is raised, the page shows it landing, and the comment is now permanent
     * evidence about the wrong test. The marker it was actually about carries nothing, and the marker
     * it hit gains an objection nobody can explain.
     *
     * <p>So this opens one marker, closes it, opens ANOTHER, and writes there. The half that fails
     * loudly is the store; the half that fails quietly is the list on screen, which must show this
     * marker's comments and not the first one's.
     */
    @Test
    void aCommentWrittenInAReusedModalIsFiledAgainstTheMarkerThatIsOpenAndNotTheOneBefore() {
        String aboutTheFirst = "the argument here is fine";
        writeComment(Seeds.PROVEN_1, VERDICT_STAGE, "anna", aboutTheFirst);

        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();
        assertThat(commentsOnScreen())
                .as("the fixture's comment is not on the marker it was written about, so this test "
                        + "would go on to prove nothing about a modal that had never shown one")
                .hasSize(1);
        closeModal();

        openMarker(Seeds.NO_ARTIFACT_FILE);
        awaitCommentBox();
        assertThat(commentsOnScreen())
                .as("the first marker's comment is still on screen under the second marker's "
                        + "heading — a reviewer is now reading somebody's judgement of a different "
                        + "artifact as though it were about this one")
                .isEmpty();

        String aboutTheSecond = "not unprovable — the harness just cannot see the generated accessor";
        tabTo("#cmttext", "");
        page.keyboard().type(aboutTheSecond);
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelectorAll('#cmtlist .cmt-one').length === 1");

        assertThat(storedComments(Seeds.NO_ARTIFACT)).extracting(MarkerComment::text)
                .as("the comment was filed against a marker other than the one whose modal was open")
                .containsExactly(aboutTheSecond);
        assertThat(storedComments(Seeds.PROVEN_1)).extracting(MarkerComment::text)
                .as("the first marker gained a comment nobody wrote about it")
                .containsExactly(aboutTheFirst);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * THE MARKS ARE COUNTED BY THE SERVER OVER THE WHOLE BACKLOG — NOT BY THE PAGE OVER A PAGE.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED, and it is the failure this feature exists to prevent
     * rather than a tidiness point. {@code /api/comments} is BOUNDED — {@code PAGE_MAX} rows — so a
     * page that marked rows by tallying the comments it received would mark the markers whose comments
     * happened to be recent and leave every other commented marker looking exactly like a marker
     * nobody has ever written about. "Which previous markers have negative comments" would then be
     * answered wrongly, silently, and only once the store had grown enough to be worth asking.
     *
     * <p>So the test is that the page asks for the INDEX and never for the export: with no page in the
     * browser at all there is no page boundary for a marker to fall off. That the index itself covers
     * the whole table is {@code CommentApiTest.TheIndexTheMarksAreDrawnFrom} — a property of one SQL
     * GROUP BY, which is where it can be asserted at a size no browser test should be paying for.
     */
    @Test
    void theTablesAreMarkedFromTheServersIndexAndNotFromAPageOfCommentsTheBrowserCounted() {
        writeComment(Seeds.PROVEN_1, REPRODUCER_STAGE, AUTHOR, THE_COMMENT);
        writeComment(Seeds.PROVEN_1, VERDICT_STAGE, "anna", "and the verdict overstates it");
        writeComment(Seeds.SKIPPED_WITH_TEXT, "fixer", "kolya", "this fix is a revert in disguise");

        openDashboard();
        page.waitForFunction("() => document.querySelectorAll('#suspicions .cmt-mark').length === 2");

        assertThat(fetched("/api/comments/index"))
                .as("the page never asked the server to count the comments, so whatever the marks are "
                        + "drawn from, it is something the browser worked out for itself")
                .isTrue();
        assertThat(requestsForThePagedExport())
                .as("the page downloaded the bounded export to draw its marks. It is correct today "
                        + "and wrong at PAGE_MAX+1 comments, at which point commented markers start "
                        + "rendering as uncommented ones with nothing to show that they have")
                .isEmpty();

        // AND THE COUNTS ARE THE SERVER'S NUMBERS, not a tally the page kept: exactly the two markers
        // that carry comments, with the counts the store holds.
        assertThat(page.evaluate("() => COMMENTS.counts"))
                .isEqualTo(Map.of(Seeds.PROVEN_1, 2, Seeds.SKIPPED_WITH_TEXT, 1));

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * Every request the browser made for {@code /api/comments} itself — the bounded export, and NOT
     * {@code /api/comments/index}, which is a different path with a different contract.
     */
    private List<String> requestsForThePagedExport() {
        return responses.stream().map(Answered::url)
                .filter(url -> url.replaceFirst("\\?.*$", "").endsWith("/api/comments"))
                .toList();
    }

    // ---- the keyboard, as a person has it --------------------------------------------------------

    /**
     * TAB UNTIL THE FOCUS IS ON THE THING, or fail saying where it got to instead.
     *
     * <p>NO {@code locator.focus()} ANYWHERE IN THIS CLASS, deliberately: focusing an element from the
     * test is exactly the assumption under test. What has to be true is that the sequential focus order
     * REACHES the box — an element a keyboard user cannot arrive at is an element they do not have, and
     * {@code focus()} would report it as present.
     *
     * @param selector what the focused element must match
     * @param text     text it must also contain, or {@code ""} for any
     */
    private void tabTo(String selector, String text) {
        Map<String, Object> target = Map.of("selector", selector, "text", text);
        for (int presses = 0; presses <= 30; presses++) {
            Boolean arrived = (Boolean) page.evaluate("""
                    ({selector, text}) => {
                      const el = document.activeElement;
                      return !!el && el.matches(selector)
                        && (!text || (el.textContent || '').includes(text));
                    }""", target);
            if (Boolean.TRUE.equals(arrived)) {
                return;
            }
            page.keyboard().press("Tab");
        }
        throw new AssertionError("thirty presses of Tab never reached " + selector
                + (text.isEmpty() ? "" : " (\"" + text + "\")")
                + ", so there is no keyboard route to it. Focus ended on: "
                + page.evaluate("""
                        () => { const el = document.activeElement;
                                return el ? (el.tagName + '#' + el.id + '.' + el.className)
                                          : 'nothing'; }"""));
    }
}
