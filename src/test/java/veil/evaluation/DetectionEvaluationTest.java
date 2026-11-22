package veil.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import veil.detection.DetectionEngine;
import veil.models.DetectedEntity;
import veil.models.PrivacyProfile;
import veil.utils.JsonUtil;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluates VEIL's DetectionEngine across a 200-prompt labelled test dataset
 * (100 sensitive + 100 benign prompts) and reports Precision, Recall, FPR, and FNR.
 *
 * Note: Benchmark metrics reflect performance on this curated test dataset only
 * and do not represent a real-world privacy guarantee.
 */
class DetectionEvaluationTest {

    @Test
    void evaluates200LabelledPromptsAndReportsMetrics() throws Exception {
        InputStream is = getClass().getResourceAsStream("/evaluation/evaluation_prompts_200.json");
        assertNotNull(is, "Missing evaluation_prompts_200.json test resource");

        List<Map<String, Object>> dataset = JsonUtil.mapper()
                .readValue(is, new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(200, dataset.size(), "Evaluation dataset must contain 200 prompts");

        DetectionEngine engine = new DetectionEngine();
        PrivacyProfile profile = new PrivacyProfile("eval", "Evaluation", "Full evaluation profile", false);
        profile.setCodeIdentifiers(List.of("PROD_CLUSTER_MASTER", "INTERNAL_SIGNING_SECRET"));

        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (Map<String, Object> row : dataset) {
            String prompt = String.valueOf(row.get("prompt"));
            boolean expectedSensitive = Boolean.TRUE.equals(row.get("sensitive"));
            List<DetectedEntity> found = engine.detect(prompt, profile);
            boolean predictedSensitive = !found.isEmpty();

            if (expectedSensitive && predictedSensitive) tp++;
            else if (!expectedSensitive && predictedSensitive) fp++;
            else if (!expectedSensitive) tn++;
            else fn++;
        }

        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double fpr = (fp + tn) == 0 ? 0.0 : (double) fp / (fp + tn);
        double fnr = (tp + fn) == 0 ? 0.0 : (double) fn / (tp + fn);

        System.out.printf("VEIL 200-Prompt Evaluation -> TP=%d FP=%d TN=%d FN=%d | Precision=%.3f Recall=%.3f FPR=%.3f FNR=%.3f%n",
                tp, fp, tn, fn, precision, recall, fpr, fnr);

        assertTrue(precision >= 0.90, "Precision below 0.90: " + precision);
        assertTrue(recall >= 0.90, "Recall below 0.90: " + recall);
    }
}
