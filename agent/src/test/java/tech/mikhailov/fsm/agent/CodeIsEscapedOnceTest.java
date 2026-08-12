package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DIFF CAME OUT OF A REPOSITORY AND WENT INTO THE PAGE UNESCAPED.
 *
 * <p>{@code code()} concatenated its argument straight into a {@code <pre>}, and one caller handed
 * it {@code git diff} — so a patch touching a line containing a {@code <} wrote markup into the
 * page. Escaping in one place is what makes that impossible to reintroduce by adding a caller.
 *
 * <p>The colouring is one pass over one alternation, so a keyword inside a string stays inside the
 * string and a quote inside a comment does not open one. Four separate replacements over the same
 * text is how {@code // the "public" API} comes out with half a comment and a stray keyword.
 */
class CodeIsEscapedOnceTest {

    private static String render(String name, String text) throws Exception {
        Method m = Dashboard.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text);
    }

    @Test
    @DisplayName("markup in source becomes text, not markup")
    void escaped() throws Exception {
        String out = render("code", "List<String> a = new ArrayList<>();\n<script>alert(1)</script>");
        assertFalse(out.contains("<script>"), "a repository decides what is in here: " + out);
        assertTrue(out.contains("&lt;script&gt;"), out);
        assertTrue(out.contains("List&lt;String&gt;"), out);
    }

    @Test
    @DisplayName("markup in a diff becomes text too — this is the one that shipped")
    void diffEscaped() throws Exception {
        String out = render("diff", "-  if (a<b) {\n+  if (a < b) {");
        assertFalse(out.contains("if (a<b)"), "unescaped: " + out);
        assertTrue(out.contains("a&lt;b"), out);
    }

    @Test
    @DisplayName("a diff is coloured by what each line does")
    void diffColoured() throws Exception {
        String out = render("diff", "--- a/A.java\n+++ b/A.java\n@@ -1,3 +1,3 @@\n-old\n+new\n same");
        assertTrue(out.contains("<span class=dr>-old</span>"), out);
        assertTrue(out.contains("<span class=da>+new</span>"), out);
        assertTrue(out.contains("<span class=dh>@@ -1,3 +1,3 @@</span>"), out);
        assertTrue(out.contains(" same"), "context stays plain: " + out);
    }

    @Test
    @DisplayName("a keyword inside a string is not a keyword")
    void oneAlternation() throws Exception {
        String out = render("code", "String s = \"return null if public\";");
        assertTrue(out.contains("<span class=s>&quot;return null if public&quot;</span>")
                        || out.contains("<span class=s>\"return null if public\"</span>"),
                "the whole literal is one span: " + out);
        assertFalse(out.replace("<span class=s>", "").contains("<span class=k>return</span> null"),
                "no keyword span may open inside the string: " + out);
    }

    @Test
    @DisplayName("a quote inside a comment does not open a string")
    void quoteInComment() throws Exception {
        String out = render("code", "// the \"public\" API\nint x = 1;");
        assertTrue(out.contains("<span class=c>// the &quot;public&quot; API</span>")
                        || out.contains("<span class=c>// the \"public\" API</span>"),
                "the comment is one span, quote and all: " + out);
        assertTrue(out.contains("<span class=k>int</span>"), "and code after it still colours");
    }

    @Test
    @DisplayName("nothing renders as nothing")
    void empty() throws Exception {
        assertTrue(render("code", "").isEmpty());
        assertTrue(render("diff", "   ").isEmpty());
    }
}
