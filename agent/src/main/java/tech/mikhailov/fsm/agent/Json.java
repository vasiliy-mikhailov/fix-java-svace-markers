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
        int open = json.indexOf('"', k + key.length() + 3);
        if (open < 0) {
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
