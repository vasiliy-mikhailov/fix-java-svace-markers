package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hand-rolled CSV reader.
 *
 * <p>The 356-marker fixture is four fully quoted columns with no surprises, so it exercises almost
 * none of this. A full Svace export carries the warning text as a fifth column of free-form prose, and
 * that is where the quoting rules earn their keep: get one wrong and every column after it shifts by
 * one, which turns the File column into a sentence and the Line column into a path. Every case here is
 * a shape that shift can come from.
 */
class CsvTest {

    private static List<List<String>> parse(String text) {
        return Csv.parse(text);
    }

    @Test
    void aCommaInsideQuotesIsDataAndNotASeparator() {
        assertEquals(List.of(List.of("Major", "leak, not closed on all paths", "7")),
                parse("\"Major\",\"leak, not closed on all paths\",\"7\""));
    }

    @Test
    void aDoubledQuoteIsOneQuoteCharacter() {
        assertEquals(List.of(List.of("he said \"close it\"", "We\"ird.java")),
                parse("\"he said \"\"close it\"\"\",\"We\"\"ird.java\""));
    }

    @Test
    void aFieldOfNothingButDoubledQuotesIsThoseQuotes() {
        assertEquals(List.of(List.of("\"", "\"\"")), parse("\"\"\"\",\"\"\"\"\"\""));
    }

    @Test
    void aNewlineInsideQuotesIsDataSoAMultiLineWarningStaysOneRow() {
        assertEquals(List.of(List.of("a\nb", "7")), parse("\"a\nb\",\"7\""));
        assertEquals(List.of(List.of("a\r\nb", "7")), parse("\"a\r\nb\",\"7\""),
                "CR is only dropped OUTSIDE quotes; inside, it is part of the message");
    }

    @Test
    void crIsDroppedWhereverItAppearsOutsideQuotesSoCrlfEndsARowExactlyOnce() {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")), parse("a,b\r\nc,d\r\n"));
        // A lone CR is NOT a row terminator: it vanishes and the row runs on. That is what makes a
        // classic-Mac export parse as one enormous row rather than as garbage rows — visible, and
        // therefore fixable, instead of silently producing 355 bad_rows.
        assertEquals(List.of(List.of("a", "bc", "d")), parse("a,b\rc,d"));
    }

    @Test
    void aQuoteMayOpenMidFieldWhichTheRfcCallsMalformedAndThisParserAccepts() {
        // Rejecting it would turn rows the pipeline has ingested for months into errors.
        assertEquals(List.of(List.of("abc")), parse("a\"b\"c"));
        assertEquals(List.of(List.of("HANDLE_LEAK")), parse("HAN\"DLE\"_LEAK"));
    }

    @Test
    void aFileThatDoesNotEndInANewlineStillYieldsItsLastRow() {
        // Dropping it would silently lose the last marker of any report truncated at write time — the
        // one case where losing a row is most likely and least visible.
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")), parse("a,b\nc,d"));
        // A last row of ONE field is the case the two halves of that guard disagree on: the row is
        // still empty when the field ends, so only the field's own length keeps it.
        assertEquals(List.of(List.of("a"), List.of("b")), parse("a\nb"));
        assertEquals(List.of(List.of("a")), parse("a"));
    }

    @Test
    void aTrailingCommaStillYieldsTheEmptyFieldAfterIt() {
        assertEquals(List.of(List.of("a", "b", "")), parse("a,b,"));
        assertEquals(List.of(List.of("a", "b", "")), parse("a,b,\n"));
    }

    @Test
    void anUnterminatedQuoteRunsToTheEndOfTheFileRatherThanThrowing() {
        assertEquals(List.of(List.of("a", "b\nc,d\n")), parse("a,\"b\nc,d\n"));
    }

    @Test
    void aQuoteAtTheVeryEndOfTheInputClosesTheFieldRatherThanDoublingUp() {
        // The lookahead for a doubled quote at the very end has nothing to compare against, so it is
        // never a match — and reading past the end has to be guarded or it throws where the field
        // simply closes.
        assertEquals(List.of(List.of("a", "7\"")), parse("a,\"7\"\""));
        assertEquals(List.of(List.of("a", "7")), parse("a,\"7\""));
    }

    @Test
    void aRowOfNothingButEmptyFieldsIsDroppedSoABlankLineIsNotARow() {
        assertEquals(List.of(List.of("a")), parse("\n\na\n\n"));
        assertEquals(List.of(List.of("a")), parse(",,,\na\n"),
                "a row of separators alone carries no marker either");
        assertEquals(List.of(), parse(""));
        assertEquals(List.of(), parse("\n\n"));
    }

    @Test
    void aRowOfWhitespaceIsARowBecauseAWhitespaceCellIsNotAnEmptyCell() {
        // The drop rule is `x !== ''`, not "is blank": a row of spaces IS counted in csv_rows and then
        // fails the per-field checks as a bad_row. That is the honest place for it to fail — csv_rows
        // is what an operator compares against the scanner's own total.
        assertEquals(List.of(List.of("   ")), parse("   \n"));
    }

    @Test
    void raggedRowsAreReturnedAtWhateverWidthTheyHave() {
        // Padding them would hide the very shift this parser exists to prevent.
        assertEquals(List.of(List.of("a", "b"), List.of("c"), List.of("d", "e", "f")),
                parse("a,b\nc\nd,e,f\n"));
    }

    @Test
    void aSurrogatePairSurvivesTheCodeUnitLoop() {
        // Iteration is over UTF-16 code units. A surrogate is never equal to a
        // delimiter, so splitting one across iterations is harmless and rejoining it is exact.
        assertEquals(List.of(List.of("😀.java", "7")), parse("😀.java,7"));
    }

    @Test
    void aByteOrderMarkIsJustACharacterHereAndIsTrimmedLater() {
        // The parser does not strip it; Js.trim does, when the header cell is normalised. Pinning it
        // at this level says WHERE the responsibility lives.
        char bom = 0xFEFF;
        assertEquals(List.of(List.of(bom + "Severity", "Checker")), parse(bom + "Severity,Checker"));
    }
}
