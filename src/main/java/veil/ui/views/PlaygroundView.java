package veil.ui.views;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.models.ChatCompletionRequest;
import veil.models.ChatCompletionResponse;
import veil.models.ChatMessage;
import veil.models.DetectedEntity;
import veil.models.ProviderType;
import veil.models.SanitizationResult;

import java.util.List;
import java.util.UUID;

/**
 * Screen H: Prompt Playground — supporting debugging & verification tool to inspect
 * detection, sanitization, policy blocking, and live provider restoration.
 */
public class PlaygroundView extends VBox {

    public PlaygroundView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(18);

        Label kicker = new Label("08 / DIAGNOSTIC PLAYGROUND");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Prompt Playground");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("Supporting test bench for inspecting detection, masking, policy blocks, and genuine provider round-trips. In daily work, route your AI tools through the Local Gateway instead.");
        desc.getStyleClass().add("body-muted");
        desc.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        ComboBox<ProviderType> providerCombo = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerCombo.setValue(core.getConfigManager().getConfig().getGateway().getActiveProvider());

        TextArea inputArea = new TextArea();
        inputArea.setPromptText("Enter a prompt to test detection & sanitization (e.g., server IP, email, PAN, or sample secret)...");
        inputArea.setPrefRowCount(5);
        inputArea.setWrapText(true);

        TextArea sanitizedArea = new TextArea();
        sanitizedArea.setEditable(false);
        sanitizedArea.setPrefRowCount(5);
        sanitizedArea.setWrapText(true);
        sanitizedArea.setPromptText("Sanitized output will appear here...");

        TextArea findingsArea = new TextArea();
        findingsArea.setEditable(false);
        findingsArea.setPrefRowCount(4);
        findingsArea.getStyleClass().add("mono-text");

        TextArea responseArea = new TextArea();
        responseArea.setEditable(false);
        responseArea.setPrefRowCount(5);
        responseArea.setWrapText(true);
        responseArea.setPromptText("Actual provider response (after contextual token restoration) will appear here when sent...");

        Label statusLabel = new Label("Ready. Active profile: " + core.getActiveProfile().getName());
        statusLabel.getStyleClass().add("body-muted");

        Button inspectBtn = new Button("Run Detection & Sanitize");
        inspectBtn.getStyleClass().add("btn-primary");
        inspectBtn.setOnAction(e -> {
            String prompt = inputArea.getText();
            if (prompt == null || prompt.isBlank()) {
                statusLabel.setText("Enter a prompt first.");
                return;
            }
            try {
                String sessionId = "pg-" + UUID.randomUUID().toString().substring(0, 8);
                SanitizationResult res = core.sanitizeText(sessionId, prompt, core.getActiveProfile());
                StringBuilder fb = new StringBuilder();
                fb.append("Session: ").append(sessionId)
                        .append(" | Detected: ").append(res.getDetectedEntities().size())
                        .append(" | Blocked: ").append(res.isBlocked()).append("\n");
                for (DetectedEntity ent : res.getDetectedEntities()) {
                    fb.append("  • [").append(ent.getCategory().name()).append("] ")
                            .append(ent.getRuleLabel()).append(" via ").append(ent.getDetectorName())
                            .append(" (span ").append(ent.getStartIndex()).append("..").append(ent.getEndIndex()).append(")\n");
                }
                findingsArea.setText(fb.toString());
                if (res.isBlocked()) {
                    sanitizedArea.setText("[BLOCKED] " + res.getBlockReason());
                    statusLabel.setText("Blocked by active privacy policy.");
                } else {
                    sanitizedArea.setText(res.getSanitizedText());
                    statusLabel.setText("Sanitized locally (not yet sent to provider).");
                }
            } catch (Exception ex) {
                statusLabel.setText("Error: " + ex.getMessage());
            }
        });

        Button sendBtn = new Button("Send to Configured Provider");
        sendBtn.getStyleClass().add("btn-secondary");
        sendBtn.setOnAction(e -> {
            String prompt = inputArea.getText();
            if (prompt == null || prompt.isBlank()) {
                statusLabel.setText("Enter a prompt first.");
                return;
            }
            ProviderType prov = providerCombo.getValue();
            statusLabel.setText("Sending sanitized request to " + prov.getDisplayName() + "...");
            new Thread(() -> {
                try {
                    ChatCompletionRequest req = new ChatCompletionRequest();
                    req.setMessages(List.of(new ChatMessage("user", prompt)));
                    String sessionId = "pg-" + UUID.randomUUID().toString().substring(0, 8);
                    ChatCompletionResponse resp = core.processChatCompletion(req, prov, sessionId, "PLAYGROUND");
                    Platform.runLater(() -> {
                        responseArea.setText(resp.getFirstAssistantContent());
                        statusLabel.setText("Received genuine response from " + prov.getDisplayName() + " and applied token restoration.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        responseArea.setText("[ERROR] " + ex.getMessage());
                        statusLabel.setText("Request failed: " + ex.getMessage());
                    });
                }
            }).start();
        });

        HBox compareRow = new HBox(16,
                labeledBox("ORIGINAL PROMPT", inputArea),
                labeledBox("SANITIZED OUTGOING PAYLOAD", sanitizedArea)
        );
        HBox.setHgrow(compareRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(compareRow.getChildren().get(1), Priority.ALWAYS);

        getChildren().addAll(
                kicker, title, desc, div,
                new HBox(10, new Label("Provider:"), providerCombo, inspectBtn, sendBtn),
                statusLabel,
                compareRow,
                labeledBox("DETECTION FINDINGS", findingsArea),
                labeledBox("RESTORED PROVIDER RESPONSE", responseArea)
        );
    }

    private VBox labeledBox(String labelText, TextArea area) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("section-kicker");
        VBox box = new VBox(6, lbl, area);
        return box;
    }
}
