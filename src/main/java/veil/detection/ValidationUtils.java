package veil.detection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cryptographic/mathematical validators and heuristics to minimize false positives.
 */
public final class ValidationUtils {

    // Verhoeff multiplication table
    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    // Verhoeff permutation table
    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    private static final Set<String> PLACEHOLDER_MARKERS = Set.of(
            "changeme", "example", "placeholder", "your_api_key", "your-api-key",
            "insert_here", "replace_me", "dummy", "sample", "test_key", "test-key",
            "fake", "mock", "none", "null", "undefined", "todo", "xxx", "xxxx",
            "password", "secret", "pass123", "your_password", "your-password",
            "your_token", "your-token", "<api_key>", "<password>", "<token>"
    );

    private ValidationUtils() {
    }

    /**
     * Validates a 12-digit Indian Aadhaar number using the official Verhoeff checksum
     * and leading-digit rule (first digit must be 2..9).
     */
    public static boolean isValidAadhaar(String raw) {
        String digits = raw.replaceAll("[\\s-]", "");
        if (digits.length() != 12 || !digits.matches("\\d{12}")) {
            return false;
        }
        char first = digits.charAt(0);
        if (first == '0' || first == '1') {
            return false;
        }
        // Reject trivially repeated digits
        if (digits.chars().distinct().count() <= 2) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = VERHOEFF_D[c][VERHOEFF_P[i % 8][digit]];
        }
        return c == 0;
    }

    /**
     * Validates a payment card number (13–19 digits) using the Luhn checksum.
     */
    public static boolean isValidLuhn(String raw) {
        String digits = raw.replaceAll("[\\s-]", "");
        if (digits.length() < 13 || digits.length() > 19 || !digits.matches("\\d+")) {
            return false;
        }
        if (digits.chars().distinct().count() <= 2) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /**
     * Validates an Indian PAN card format: 5 letters (4th is valid entity code), 4 digits, 1 letter.
     */
    public static boolean isValidPan(String candidate) {
        if (candidate == null || candidate.length() != 10) {
            return false;
        }
        if (!candidate.matches("[A-Z]{3}[ABCFGHLJPTF][A-Z][0-9]{4}[A-Z]")) {
            return false;
        }
        return true;
    }

    /**
     * Validates that an IPv4 string has four 0..255 octets and is not a version number like 1.0.0.0.
     */
    public static boolean isValidIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return false;
            }
            if (parts[i].length() > 1 && parts[i].startsWith("0")) {
                return false;
            }
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return false;
                }
                octets[i] = v;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Ignore common non-sensitive broadcast/wildcard placeholders
        return !(octets[0] == 0 && octets[1] == 0 && octets[2] == 0 && octets[3] == 0);
    }

    /**
     * Computes Shannon entropy in bits per character.
     */
    public static double shannonEntropy(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : value.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }
        double entropy = 0.0;
        int len = value.length();
        for (int count : freq.values()) {
            double p = (double) count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /**
     * Detects obvious documentation placeholders so they are not flagged as live secrets.
     */
    public static boolean isLikelyPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_MARKERS.contains(lower)) {
            return true;
        }
        if (lower.startsWith("${") || lower.startsWith("<") && lower.endsWith(">")) {
            return true;
        }
        for (String marker : PLACEHOLDER_MARKERS) {
            if (marker.length() >= 5 && lower.contains(marker)) {
                return true;
            }
        }
        // Repeated single character like "aaaaaaaaaa" or "********"
        if (lower.chars().distinct().count() <= 2) {
            return true;
        }
        return false;
    }
}
