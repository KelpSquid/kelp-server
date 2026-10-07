package kelpserver;

import java.time.Year;
import java.util.Map;

/**
 * The age rules for social features, by region. Below a region's age of digital consent (13 in the US under COPPA,
 * 13 to 16 in the EU under GDPR article 8, depending on the country), everything social starts OFF and only a parent
 * can turn it on. From that age to 17 it starts ON, and a parent can still manage it. In the UK, under-18 profiles
 * start private (the Children's Code). Only the birth YEAR is asked, with no hint why, and an age is worked out the
 * careful way: as if this year's birthday hasn't happened yet.
 *
 * These ages should be checked by someone who knows the law before launch.
 */
public final class Rules {
    private Rules() {
    }

    /** The social features a parent can switch. */
    public static final String[] FEATURES = {"profile", "posting", "comments", "dms", "multiplayer", "chat", "voice"};

    // GDPR article 8 ages, where a country set its own (the rest of the EU uses the default of 16)
    private static final Map<String, Integer> EU_AGES = Map.ofEntries(
            Map.entry("AT", 14), Map.entry("BE", 13), Map.entry("BG", 14), Map.entry("HR", 16), Map.entry("CY", 14),
            Map.entry("CZ", 15), Map.entry("DK", 13), Map.entry("EE", 13), Map.entry("FI", 13), Map.entry("FR", 15),
            Map.entry("DE", 16), Map.entry("GR", 15), Map.entry("HU", 16), Map.entry("IE", 16), Map.entry("IT", 14),
            Map.entry("LV", 13), Map.entry("LT", 14), Map.entry("LU", 16), Map.entry("MT", 13), Map.entry("NL", 16),
            Map.entry("PL", 16), Map.entry("PT", 13), Map.entry("RO", 16), Map.entry("SK", 16), Map.entry("SI", 15),
            Map.entry("ES", 14), Map.entry("SE", 13),
            // EEA, which follows the same rules
            Map.entry("IS", 13), Map.entry("LI", 16), Map.entry("NO", 13));

    /** The age a player in this region can use social features without a parent. */
    public static int consentAge(String region) {
        Integer eu = EU_AGES.get(region);
        return eu != null ? eu : 13;
    }

    /** Whether under-18 profiles start private here. */
    public static boolean privateUnder18(String region) {
        return "GB".equals(region);
    }

    /** A region code: two capital letters, like US or DE. */
    public static boolean validRegion(String region) {
        return region != null && region.matches("[A-Z]{2}");
    }

    /** The youngest someone born in this year could be: as if this year's birthday hasn't come yet. */
    public static int age(int birthYear) {
        return Year.now().getValue() - birthYear - 1;
    }

    /** "child" (below the region's consent age), "teen" (from there to 17) or "adult". */
    public static String group(int birthYear, String region) {
        int age = age(birthYear);
        if (age < consentAge(region)) return "child";
        return age < 18 ? "teen" : "adult";
    }

    public static boolean validBirthYear(int year) {
        int now = Year.now().getValue();
        return year >= now - 120 && year <= now - 3;
    }
}
