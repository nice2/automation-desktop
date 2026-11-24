package checker;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

//Tag comparison helpers. Tags are cut to 20 characters per line and abbreviated differently in different
//places ("Low Heat 13FL SouthT" on the zone, "LoHeat 13FL SouthTwr" on the LED), so they are compared
//loosely: lowercase letters and digits only, and a tag that is the start of the other counts as a match.
final class Text {

    private Text() {}

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    //Removes the longest leading word from the list, e.g. ("LoHeat 13FL SouthTwr", "loheat", "low heat") -> "13flsouthtwr".
    //Returns the normalized remainder.
    static String stripWord(String tag, String... words) {
        String t = norm(tag);
        String best = "";
        for (String w : words) {
            String n = norm(w);
            if (t.startsWith(n) && n.length() > best.length()) best = n;
        }
        return t.substring(best.length());
    }

    //Same as stripWord but keeps the original spelling, for display
    static String stripWordDisplay(String tag, String... words) {
        String t = tag.trim().replaceAll("\\s{2,}", " ");
        String lower = t.toLowerCase();
        String best = "";
        for (String w : words) if (lower.startsWith(w.toLowerCase()) && w.length() > best.length()) best = w;
        return t.substring(best.length()).trim();
    }

    //1 = same, 0.95 = one is the start of the other (a cut-off tag), otherwise how many letter pairs they share (0..1)
    static double similarity(String a, String b) {
        a = norm(a);
        b = norm(b);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (Math.min(a.length(), b.length()) >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.95;
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) pairs.merge(a.substring(i, i + 2), 1, Integer::sum);
        int shared = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String p = b.substring(i, i + 2);
            Integer n = pairs.get(p);
            if (n != null && n > 0) {
                shared++;
                pairs.put(p, n - 1);
            }
        }
        int total = (a.length() - 1) + (b.length() - 1);
        return total <= 0 ? 0 : 0.9 * (2.0 * shared / total); //kept below the 0.95 of a prefix match
    }

    //The candidate whose key is most like the text, or null if there are no candidates
    static <T> T best(String text, Collection<T> candidates, Function<T, String> key) {
        T best = null;
        double best_score = -1;
        for (T c : candidates) {
            double s = similarity(text, key.apply(c));
            if (s > best_score) {
                best_score = s;
                best = c;
            }
        }
        return best;
    }
}
