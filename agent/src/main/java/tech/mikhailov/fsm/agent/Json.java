package tech.mikhailov.fsm.agent;

/**
 * ONE FIELD OUT OF ONE FLAT LINE.
 *
 * <p>Every file this program writes — the trace, the settlements, the feedback, the cases — is a flat
 * map of strings written by {@link Settlement#escape}. Reading one back is a scan, and staying a scan
 * is deliberate: a malformed line costs the field, where a parser would refuse the whole file and
 * take a dashboard or a test run down with it.
 */
final class Json {

    private Json() {
    }

    static String field(String json, String key) {
        int k = json.indexOf('"' + key + "\":");
        if (k < 0) {
            return "";
        }
        // A VALUE IS NOT ALWAYS QUOTED. Settlement writes booleans and Feedback writes an int
        // unquoted, and scanning for the next quote then skips past them and finds the following
        // KEY's quote instead — which is why red_verified read as empty for every marker that had
        // genuinely gone red, and the semaphore never lit.
        int colon = json.indexOf(':', k + key.length());
        int scan = colon + 1;
        while (scan < json.length() && json.charAt(scan) == ' ') {
            scan++;
        }
        if (scan < json.length() && json.charAt(scan) != '"') {
            int stop = scan;
            while (stop < json.length() && ",}".indexOf(json.charAt(stop)) < 0) {
                stop++;
            }
            return json.substring(scan, stop).trim();
        }
        int open = scan;
        if (open >= json.length()) {
            return "";
        }
        StringBuilder v = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                v.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else if (c == '"') {
                break;
            } else {
                v.append(c);
            }
        }
        return v.toString();
    }
}
