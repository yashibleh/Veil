package veil.detection;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Developer-focused detector for configured internal code identifiers,
 * proprietary hostnames, and private employee/project IDs.
 */
public class CodeIdentifierDetector implements Detector {

    // Internal database/service hostnames (e.g., prod-db-01.corp.internal, billing-master.internal.local)
    private static final Pattern INTERNAL_HOST = Pattern.compile(
            "\\b([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.(?:internal|corp|intranet|private|lan|local))\\b");

    // Private employee / internal ticket / account IDs (e.g., EMP-48291, CUST-99412)
    private static final Pattern PRIVATE_ID = Pattern.compile(
            "\\b((?:EMP|STAFF|CUST|ACCT|PROJ|CONF)-[A-Z0-9]{4,12})\\b");

    @Override
    public String getName() {
        return "CodeIdentifierDetector";
    }

    @Override
    public String getDescription() {
        return "Detects configured source-code identifiers, internal hostnames, and structured private identifiers.";
    }

    @Override
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        List<DetectedEntity> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        // 1. User/profile configured code identifiers
        if (profile != null && profile.getCodeIdentifiers() != null) {
            for (String identifier : profile.getCodeIdentifiers()) {
                if (identifier == null || identifier.isBlank()) {
                    continue;
                }
                Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(identifier.trim()) + "(?![A-Za-z0-9_])");
                Matcher m = p.matcher(text);
                while (m.find()) {
                    results.add(new DetectedEntity(
                            EntityCategory.CODE_IDENTIFIER,
                            m.group(),
                            m.start(),
                            m.end(),
                            getName(),
                            1.0,
                            "Configured Code Identifier"
                    ));
                }
            }
        }

        // 2. Internal hostnames
        Matcher hostMatcher = INTERNAL_HOST.matcher(text);
        while (hostMatcher.find()) {
            results.add(new DetectedEntity(
                    EntityCategory.CODE_IDENTIFIER,
                    hostMatcher.group(1),
                    hostMatcher.start(1),
                    hostMatcher.end(1),
                    getName(),
                    0.88,
                    "Internal Hostname"
            ));
        }

        // 3. Structured private identifiers
        Matcher idMatcher = PRIVATE_ID.matcher(text);
        while (idMatcher.find()) {
            results.add(new DetectedEntity(
                    EntityCategory.PRIVATE_IDENTIFIER,
                    idMatcher.group(1),
                    idMatcher.start(1),
                    idMatcher.end(1),
                    getName(),
                    0.90,
                    "Private Identifier"
            ));
        }

        return results;
    }
}
