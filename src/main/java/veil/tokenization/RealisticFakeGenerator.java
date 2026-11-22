package veil.tokenization;

import veil.models.EntityCategory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates plausible synthetic values for the REALISTIC_FAKE policy while ensuring
 * uniqueness so mappings remain safely reversible within a session.
 */
public class RealisticFakeGenerator {

    private static final List<String> SYNTHETIC_NAMES = List.of(
            "Aarav Sen", "Maya Kapoor", "Jordan Ellis", "Elena Novak",
            "Liam Mercer", "Sora Takahashi", "Nadia Okafor", "Theo Lindqvist"
    );

    private final Map<EntityCategory, AtomicInteger> counters = new ConcurrentHashMap<>();

    public String nextFake(EntityCategory category, Set<String> existingReplacements, String sourceText) {
        AtomicInteger counter = counters.computeIfAbsent(category, c -> new AtomicInteger(0));
        while (true) {
            int n = counter.incrementAndGet();
            String candidate = switch (category) {
                case EMAIL -> String.format("user%02d@synthetic.invalid", n);
                case PHONE -> String.format("+1-555-010-%04d", 1000 + (n % 8999));
                case IP_ADDRESS -> String.format("198.51.100.%d", 10 + (n % 240));
                case PERSONAL_NAME -> SYNTHETIC_NAMES.get((n - 1) % SYNTHETIC_NAMES.size())
                        + (n > SYNTHETIC_NAMES.size() ? " " + n : "");
                case AADHAAR -> String.format("9999 0000 %04d", n);
                case PAN -> String.format("ZZZZZ%04dZ", n % 10000);
                case CREDIT_CARD -> String.format("4000-0000-0000-%04d", n % 10000);
                case URL -> String.format("https://service-%02d.example.invalid/resource", n);
                case API_KEY -> String.format("sk-veil-synthetic-%06d-redacted", n);
                case ACCESS_TOKEN -> String.format("veil_synth_token_%06d", n);
                case PASSWORD -> String.format("SyntheticPass#%04d!", n);
                case DB_CREDENTIAL -> String.format("postgres://synth_user_%02d:synth_pass@db.example.invalid:5432/app", n);
                case CODE_IDENTIFIER -> String.format("synthetic_symbol_%02d", n);
                case PRIVATE_IDENTIFIER -> String.format("SYNTH-ID-%04d", n);
                case CUSTOM -> String.format("synthetic_term_%02d", n);
            };
            boolean used = existingReplacements != null && existingReplacements.contains(candidate);
            boolean inText = sourceText != null && sourceText.contains(candidate);
            if (!used && !inText) {
                return candidate;
            }
        }
    }

    public void reset() {
        counters.clear();
    }
}
