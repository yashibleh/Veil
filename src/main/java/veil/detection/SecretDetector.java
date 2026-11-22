package veil.detection;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contextual & pattern-based secret detector for API keys, access tokens,
 * JWTs, private keys, and password assignments. Uses Shannon entropy and
 * placeholder filtering to avoid flagging ordinary variables or sample values.
 */
public class SecretDetector implements Detector {

    // Known high-signal provider API key prefixes
    private static final Pattern PROVIDER_API_KEY = Pattern.compile(
            "\\b("
                    + "sk-(?:proj-|live-|test-)?[A-Za-z0-9_-]{20,}|"
                    + "sk-ant-[A-Za-z0-9_-]{20,}|"
                    + "AIza[0-9A-Za-z_-]{35}|"
                    + "AKIA[0-9A-Z]{16}|"
                    + "gh[pousr]_[A-Za-z0-9]{30,}|"
                    + "github_pat_[A-Za-z0-9_]{22,}|"
                    + "xox[baprs]-[A-Za-z0-9-]{18,}|"
                    + "SG\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}"
                    + ")\\b");

    // JSON Web Tokens (3 base64url segments)
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\b(eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b");

    // Bearer / Authorization token headers
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{20,})");

    // Contextual password assignment (config files, env files, JSON, YAML, code)
    private static final Pattern PASSWORD_ASSIGNMENT = Pattern.compile(
            "(?i)(?:password|passwd|pwd|db_pass|db_password|secret_key|client_secret)\\s*[:=]\\s*[\"']?([^\\s\"',;}{\\]]{6,128})[\"']?");

    // Generic contextual API key / access token assignment
    private static final Pattern GENERIC_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:api_key|apikey|api-key|access_token|auth_token|private_token|bearer_token)\\s*[:=]\\s*[\"']?([A-Za-z0-9._+/=-]{14,160})[\"']?");

    // PEM private key block header
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    @Override
    public String getName() {
        return "SecretDetector";
    }

    @Override
    public String getDescription() {
        return "Detects API keys, JWTs, bearer tokens, private keys, and contextual password/secret assignments with entropy and placeholder filtering.";
    }

    @Override
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        List<DetectedEntity> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        // 1. Private key blocks
        Matcher pem = PRIVATE_KEY_BLOCK.matcher(text);
        while (pem.find()) {
            results.add(new DetectedEntity(
                    EntityCategory.ACCESS_TOKEN,
                    pem.group(),
                    pem.start(),
                    pem.end(),
                    getName(),
                    0.99,
                    "PEM Private Key"
            ));
        }

        // 2. Known provider API keys
        Matcher apiKey = PROVIDER_API_KEY.matcher(text);
        while (apiKey.find()) {
            String val = apiKey.group(1);
            if (!ValidationUtils.isLikelyPlaceholder(val)) {
                double entropy = ValidationUtils.shannonEntropy(val);
                if (entropy >= 3.0) {
                    results.add(new DetectedEntity(
                            EntityCategory.API_KEY,
                            val,
                            apiKey.start(1),
                            apiKey.end(1),
                            getName(),
                            Math.min(0.99, 0.80 + (entropy / 25.0)),
                            "Provider API Key"
                    ));
                }
            }
        }

        // 3. JWTs
        Matcher jwt = JWT_PATTERN.matcher(text);
        while (jwt.find()) {
            String val = jwt.group(1);
            results.add(new DetectedEntity(
                    EntityCategory.ACCESS_TOKEN,
                    val,
                    jwt.start(1),
                    jwt.end(1),
                    getName(),
                    0.96,
                    "JWT Access Token"
            ));
        }

        // 4. Bearer tokens
        Matcher bearer = BEARER_TOKEN.matcher(text);
        while (bearer.find()) {
            String val = bearer.group(1);
            if (!ValidationUtils.isLikelyPlaceholder(val) && ValidationUtils.shannonEntropy(val) >= 3.2) {
                results.add(new DetectedEntity(
                        EntityCategory.ACCESS_TOKEN,
                        val,
                        bearer.start(1),
                        bearer.end(1),
                        getName(),
                        0.93,
                        "Bearer Token"
                ));
            }
        }

        // 5. Contextual passwords
        Matcher pwd = PASSWORD_ASSIGNMENT.matcher(text);
        while (pwd.find()) {
            String val = pwd.group(1);
            if (!ValidationUtils.isLikelyPlaceholder(val) && !looksLikeCodeReference(val)) {
                results.add(new DetectedEntity(
                        EntityCategory.PASSWORD,
                        val,
                        pwd.start(1),
                        pwd.end(1),
                        getName(),
                        0.91,
                        "Contextual Password"
                ));
            }
        }

        // 6. Generic contextual API keys / tokens
        Matcher generic = GENERIC_SECRET_ASSIGNMENT.matcher(text);
        while (generic.find()) {
            String val = generic.group(1);
            if (!ValidationUtils.isLikelyPlaceholder(val)
                    && !looksLikeCodeReference(val)
                    && ValidationUtils.shannonEntropy(val) >= 3.2) {
                EntityCategory cat = generic.group(0).toLowerCase().contains("token")
                        ? EntityCategory.ACCESS_TOKEN
                        : EntityCategory.API_KEY;
                results.add(new DetectedEntity(
                        cat,
                        val,
                        generic.start(1),
                        generic.end(1),
                        getName(),
                        0.89,
                        "Contextual Secret Assignment"
                ));
            }
        }

        return results;
    }

    private boolean looksLikeCodeReference(String value) {
        return value.startsWith("env.")
                || value.startsWith("process.env")
                || value.startsWith("System.getenv")
                || value.startsWith("os.environ")
                || value.startsWith("$");
    }
}
