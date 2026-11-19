package veil.detection;

import veil.models.DetectedEntity;
import veil.models.EntityCategory;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contextual personal name detector using honorifics, explicit field labels
 * (e.g., "Employee: Priya Sharma", "Name: Rahul Verma"), and salutation cues.
 * Intentionally conservative to avoid flagging ordinary capitalized technical terms.
 */
public class NameDetector implements Detector {

    // Explicit label context: "Name: Rahul Sharma", "Employee: Anita Desai", "Author: John Doe"
    private static final Pattern LABELED_NAME = Pattern.compile(
            "(?i)\\b(?:employee|full\\s*name|name|contact|manager|owner|patient|customer|client|author|signed\\s*by|prepared\\s*by)\\s*[:\\-]\\s*([A-Z][a-z]{1,20}(?:\\s+[A-Z][a-z]{1,20}){1,2})\\b");

    // Honorific context: "Mr. Rahul Sharma", "Dr. Meera Nair"
    private static final Pattern HONORIFIC_NAME = Pattern.compile(
            "\\b(?:Mr\\.|Mrs\\.|Ms\\.|Dr\\.|Prof\\.)\\s+([A-Z][a-z]{1,20}(?:\\s+[A-Z][a-z]{1,20}){0,2})\\b");

    // Conversational intro: "my name is Priya Nair", "I am Vikram Mehta"
    private static final Pattern INTRO_NAME = Pattern.compile(
            "(?i)\\b(?:my name is|contact|ask|email|call)\\s+([A-Z][a-z]{1,20}\\s+[A-Z][a-z]{1,20})\\b");

    private static final Set<String> NON_NAME_STOPWORDS = Set.of(
            "Google Cloud", "Amazon Web", "Microsoft Azure", "Open AI", "Spring Boot",
            "Visual Studio", "Java Virtual", "Local Host", "System Admin", "Database Server",
            "Production Server", "Staging Environment", "Privacy Policy", "Access Token",
            "Error Message", "Null Pointer", "Request Body", "Response Header", "North America",
            "South Asia", "New Delhi", "New York", "San Francisco", "United States"
    );

    @Override
    public String getName() {
        return "NameDetector";
    }

    @Override
    public String getDescription() {
        return "Detects personal names appearing with honorifics, field labels, or conversational person cues.";
    }

    @Override
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        List<DetectedEntity> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        collectMatches(text, LABELED_NAME, "Labeled Personal Name", 0.87, results);
        collectMatches(text, HONORIFIC_NAME, "Honorific Personal Name", 0.91, results);
        collectMatches(text, INTRO_NAME, "Contextual Personal Name", 0.82, results);
        return results;
    }

    private void collectMatches(String text, Pattern pattern, String label, double confidence, List<DetectedEntity> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (!NON_NAME_STOPWORDS.contains(candidate)) {
                out.add(new DetectedEntity(
                        EntityCategory.PERSONAL_NAME,
                        candidate,
                        matcher.start(1),
                        matcher.end(1),
                        getName(),
                        confidence,
                        label
                ));
            }
        }
    }
}
