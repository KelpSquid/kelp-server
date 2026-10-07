package kelpserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Random tokens and one-way fingerprints (hashes), so the server never stores a token, code or key as itself. */
public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /** A random token, bytes long, as hex. */
    public static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A recovery code people can write down: KELP-XXXX-XXXX-XXXX-XXXX with no look-alike letters (0/O, 1/I). */
    public static String recoveryCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder("KELP");
        for (int group = 0; group < 4; group++) {
            code.append('-');
            for (int i = 0; i < 4; i++) code.append(letters.charAt(RANDOM.nextInt(letters.length())));
        }
        return code.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares two fingerprints in the same time whatever they hold, so timing can't give a secret away. */
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
