package veil.detection;

import veil.models.CustomRule;
import veil.models.DetectedEntity;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates user-defined literal and regular-expression rules configured on the active profile.
 */
public class CustomRuleDetector implements Detector {

    @Override
    public String getName() {
        return "CustomRuleDetector";
    }

    @Override
    public String getDescription() {
        return "Applies user-defined literal terms and custom regular expressions.";
    }

    @Override
    public List<DetectedEntity> detect(String text, PrivacyProfile profile) {
        List<DetectedEntity> results = new ArrayList<>();
        if (text == null || text.isEmpty() || profile == null || profile.getCustomRules() == null) {
            return results;
        }

        for (CustomRule rule : profile.getCustomRules()) {
            if (rule == null || !rule.isEnabled() || rule.getPattern() == null || rule.getPattern().isBlank()) {
                continue;
            }
            int flags = rule.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern compiled;
            try {
                String expr = rule.isRegex() ? rule.getPattern() : Pattern.quote(rule.getPattern());
                compiled = Pattern.compile(expr, flags);
            } catch (PatternSyntaxException ignored) {
                // Skip invalid regex without crashing the request pipeline
                continue;
            }

            Matcher matcher = compiled.matcher(text);
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    continue;
                }
                results.add(new DetectedEntity(
                        rule.getCategory(),
                        matcher.group(),
                        matcher.start(),
                        matcher.end(),
                        getName(),
                        1.0,
                        rule.getName() == null ? "Custom Rule" : rule.getName()
                ));
            }
        }
        return results;
    }
}
