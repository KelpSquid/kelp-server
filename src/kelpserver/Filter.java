package kelpserver;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks text players write (messages, titles) before anyone else sees it. Lots of players are kids, so it blocks
 * swearing and slurs (even spelled with numbers or spaces in between), and personal details: phone numbers, email
 * addresses, street addresses and links. Blocked text isn't sent; the player is told why.
 */
public final class Filter {
    private Filter() {
    }

    /** A short list of the worst words; matched after undoing common disguises. Kept small and blunt on purpose. */
    private static final List<String> WORDS = List.of(
            "fuck", "shit", "bitch", "cunt", "dick", "pussy", "asshole", "bastard", "whore", "slut", "porn", "sex",
            "nigger", "nigga", "faggot", "fag", "retard", "tranny", "kys", "killyourself");

    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\s.\\-()]*){7,}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+\\s*(?:@|\\(at\\)|\\[at\\])\\s*[\\w-]+\\s*(?:\\.|\\(dot\\)|\\[dot\\])\\s*[a-z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.|discord\\.gg|\\b[\\w-]+\\.(?:com|net|org|gg|io|co|xyz|ru|tk|me)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS = Pattern.compile("\\b\\d{1,5}\\s+\\w+(?:\\s+\\w+)?\\s+(?:street|st|avenue|ave|road|rd|lane|ln|drive|dr|court|ct|boulevard|blvd|way)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Why text can't be sent, or null if it's fine. */
    public static String problem(String text) {
        if (text == null || text.isBlank()) return "Write something first.";
        if (PHONE.matcher(text).find()) return "No phone numbers, for everyone's safety.";
        if (EMAIL.matcher(text).find()) return "No email addresses, for everyone's safety.";
        if (ADDRESS.matcher(text).find()) return "No addresses, for everyone's safety.";
        if (LINK.matcher(text).find()) return "No links, for everyone's safety.";
        String plain = squash(text);
        for (String word : WORDS) {
            if (word.length() <= 3 ? Pattern.compile("(?:^|[^a-z])" + word + "(?:[^a-z]|$)").matcher(spaced(text)).find() : plain.contains(word)) {
                return "Please keep it friendly.";
            }
        }
        return null;
    }

    /** Lowercase letters only, with look-alike numbers and symbols turned back into letters, so "f u c k" or "sh1t" match. */
    static String squash(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('@', 'a').replace('$', 's').replace('!', 'i');
        StringBuilder out = new StringBuilder();
        char last = 0;
        for (char c : t.toCharArray()) {
            if (c >= 'a' && c <= 'z' && c != last) out.append(c); // letters only, and "fuuuck" counts as "fuck"
            if (c >= 'a' && c <= 'z') last = c;
        }
        return out.toString();
    }

    private static String spaced(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
    }
}
