package tech.mikhailov.fsm.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * ONE FILE OUT OF A BROWSER, WITHOUT A FRAMEWORK.
 *
 * <p>{@code HttpServer} parses a query string and nothing else, so a file field arrives as
 * {@code multipart/form-data} that somebody has to split. This does exactly that and no more: the
 * boundary out of the content type, the parts out of the body, each part's name out of its
 * disposition, and its bytes.
 *
 * <p>SCANNED, NOT STREAMED, and bounded. A settings form carries a marker list or a source archive,
 * both of which fit in memory; streaming would be the right answer for an upload this program does
 * not have. {@link #LIMIT} is what stops a browser pointed at a DVD image from taking the dashboard
 * down with it.
 */
final class Upload {

    /** As much as one form may carry. A marker file is kilobytes; a source zip is megabytes. */
    static final int LIMIT = 64 * 1024 * 1024;

    private Upload() {
    }

    /** Whether this request is a form with files in it rather than a plain one. */
    static boolean isMultipart(HttpExchange e) {
        String type = e.getRequestHeaders().getFirst("Content-Type");
        return type != null && type.toLowerCase().startsWith("multipart/form-data");
    }

    /** Every part of the form, by field name, as bytes. Text fields included. */
    static Map<String, byte[]> parts(HttpExchange e) throws IOException {
        Map<String, byte[]> found = new LinkedHashMap<>();
        String type = e.getRequestHeaders().getFirst("Content-Type");
        int at = type == null ? -1 : type.toLowerCase().indexOf("boundary=");
        if (at < 0) {
            return found;
        }
        String boundary = type.substring(at + "boundary=".length()).replaceAll("^\"|\"$", "").strip();
        byte[] body = read(e.getRequestBody());
        byte[] mark = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        int from = indexOf(body, mark, 0);
        while (from >= 0) {
            int start = from + mark.length;
            int next = indexOf(body, mark, start);
            if (next < 0) {
                break;
            }
            // A part is headers, a blank line, then bytes — and the two bytes before the next
            // boundary are the CRLF that belongs to the boundary and not to the content.
            int blank = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (blank > 0 && blank < next) {
                String headers = new String(body, start, blank - start, StandardCharsets.ISO_8859_1);
                String name = between(headers, "name=\"", "\"");
                if (!name.isBlank()) {
                    int begin = blank + 4;
                    int end = Math.max(begin, next - 2);
                    byte[] value = new byte[end - begin];
                    System.arraycopy(body, begin, value, 0, value.length);
                    found.put(name, value);
                }
            }
            from = next;
        }
        return found;
    }

    /** One part as text, which is what every field but a zip is. */
    static String text(Map<String, byte[]> parts, String name) {
        byte[] value = parts.get(name);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > LIMIT) {
                throw new IOException("more than " + (LIMIT / 1024 / 1024) + "MB; refused");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) {
            return "";
        }
        int b = s.indexOf(close, a + open.length());
        return b < 0 ? "" : s.substring(a + open.length(), b);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
