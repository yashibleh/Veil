package veil.detection;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based detector for structured PII and network/database identifiers,
 * backed by checksum and structural validation to avoid numeric false positives.
 */
public class RegexDetector implements Detector {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,12}\\b");

    // International & Indian mobile/landline formats requiring separators or country code to avoid plain numbers
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\w)(?:\\+\\d{1,3}[\\s.-]?(?:\\(\\d{2,4}\\)[\\s.-]?)?\\d{3,5}[\\s.-]?\\d{3,5}(?:[\\s.-]?\\d{2,4})?|"
                    + "\\(\\d{3}\\)[\\s.-]?\\d{3}[\\s.-]?\\d{4}|"
                    + "\\b\\d{3}[\\s.-]\\d{3}[\\s.-]\\d{4}\\b|"
                    + "(?:\\+91[\\s.-]?|0)?[6-9]\\d{4}[\\s.-]\\d{5}\\b)");

    // Aadhaar: 4-4-4 grouped or contiguous 12 digits validated by Verhoeff checksum
    private static final Pattern AADHAAR_PATTERN = Pattern.compile(
            "(?<!\\d)([2-9]\\d{3}[\\s-]?\\d{4}[\\s-]?\\d{4})(?!\\d)");

    // Indian PAN
    private static final Pattern PAN_PATTERN = Pattern.compile(
            "\\b([A-Z]{3}[ABCFGHLJPTF][A-Z][0-9]{4}[A-Z])\\b");

    // Payment cards: 13-19 digits with optional spaces or hyphens, validated via Luhn
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?<!\\d)((?:4\\d{3}|5[1-5]\\d{2}|3[47]\\d{2}|6(?:011|5\\d{2})|2(?:22[1-9]|2[3-9]\\d|[3-6]\\d{2}|7[01]\\d|720))(?:[\\s-]?\\d{4}){2,3}(?:[\\s-]?\\d{1,4})?)(?!\\d)");

    // IPv4 address (ignores version strings prefixed with 'v' like v1.2.3.4)
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![\\dvV.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?![\\d.])");

    // Database connection URIs with embedded credentials
    private static final Pattern DB_URI_PATTERN = Pattern.compile(
            "\\b((?:jdbc:)?(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis|rediss|amqp|amqps|sqlserver)://[^\\s\"'`<>]+@[^\\s\"'`<>]+)");

    // Internal/private URLs or URLs containing sensitive query parameters
    private static final Pattern SENSITIVE_URL_PATTERN = Pattern.compile(
            "\\b(https?://(?:(?:10|127)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|[A-Za-z0-9-]+\\.(?:internal|corp|local|lan|intranet)|[^\\s\"'`<>]*(?:token=|key=|secret=|password=|auth=))[^\\s\"'`<>]*)");

    @Override
    public String getName() {
        return "RegexDetector";
    }

    @Override
    public String getDescription() {
        return "Detects structured identifiers (emails, phones, Aadhaar, PAN, cards, IPs, DB URIs, internal URLs) with checksum validation.";
    }

    @Override
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        List<DetectedEntity> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        // 1. Database connection strings with credentials
        Matcher dbMatcher = DB_URI_PATTERN.matcher(text);
        while (dbMatcher.find()) {
            results.add(new DetectedEntity(
                    EntityCategory.DB_CREDENTIAL,
                    dbMatcher.group(1),
                    dbMatcher.start(1),
                    dbMatcher.end(1),
                    getName(),
                    0.96,
                    "Database Connection URI"
            ));
        }

        // 2. Sensitive/Internal URLs
        Matcher urlMatcher = SENSITIVE_URL_PATTERN.matcher(text);
        while (urlMatcher.find()) {
            results.add(new DetectedEntity(
                    EntityCategory.URL,
                    urlMatcher.group(1),
                    urlMatcher.start(1),
                    urlMatcher.end(1),
                    getName(),
                    0.90,
                    "Internal or Authenticated URL"
            ));
        }

        // 3. Emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
        while (emailMatcher.find()) {
            String val = emailMatcher.group();
            results.add(new DetectedEntity(
                    EntityCategory.EMAIL,
                    val,
                    emailMatcher.start(),
                    emailMatcher.end(),
                    getName(),
                    0.98,
                    "Email Address"
            ));
        }

        // 4. Payment cards (Luhn validated)
        Matcher cardMatcher = CARD_PATTERN.matcher(text);
        while (cardMatcher.find()) {
            String val = cardMatcher.group(1);
            if (ValidationUtils.isValidLuhn(val)) {
                results.add(new DetectedEntity(
                        EntityCategory.CREDIT_CARD,
                        val,
                        cardMatcher.start(1),
                        cardMatcher.end(1),
                        getName(),
                        0.97,
                        "Payment Card (Luhn Verified)"
                ));
            }
        }

        // 5. Aadhaar (Verhoeff validated)
        Matcher aadhaarMatcher = AADHAAR_PATTERN.matcher(text);
        while (aadhaarMatcher.find()) {
            String val = aadhaarMatcher.group(1);
            if (ValidationUtils.isValidAadhaar(val)) {
                results.add(new DetectedEntity(
                        EntityCategory.AADHAAR,
                        val,
                        aadhaarMatcher.start(1),
                        aadhaarMatcher.end(1),
                        getName(),
                        0.95,
                        "Aadhaar Number (Verhoeff Verified)"
                ));
            }
        }

        // 6. PAN
        Matcher panMatcher = PAN_PATTERN.matcher(text);
        while (panMatcher.find()) {
            String val = panMatcher.group(1);
            if (ValidationUtils.isValidPan(val)) {
                results.add(new DetectedEntity(
                        EntityCategory.PAN,
                        val,
                        panMatcher.start(1),
                        panMatcher.end(1),
                        getName(),
                        0.94,
                        "PAN Identifier"
                ));
            }
        }

        // 7. Phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(text);
        while (phoneMatcher.find()) {
            String val = phoneMatcher.group();
            String digits = val.replaceAll("\\D", "");
            if (digits.length() >= 10 && digits.length() <= 15) {
                results.add(new DetectedEntity(
                        EntityCategory.PHONE,
                        val,
                        phoneMatcher.start(),
                        phoneMatcher.end(),
                        getName(),
                        0.88,
                        "Phone Number"
                ));
            }
        }

        // 8. IPv4 addresses
        Matcher ipMatcher = IPV4_PATTERN.matcher(text);
        while (ipMatcher.find()) {
            String val = ipMatcher.group(1);
            if (ValidationUtils.isValidIpv4(val)) {
                results.add(new DetectedEntity(
                        EntityCategory.IP_ADDRESS,
                        val,
                        ipMatcher.start(1),
                        ipMatcher.end(1),
                        getName(),
                        0.92,
                        "IPv4 Address"
                ));
            }
        }

        return results;
    }
}
