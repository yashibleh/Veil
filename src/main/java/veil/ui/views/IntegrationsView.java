package veil.ui.views;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.integrations.IntegrationDescriptor;
import veil.models.GatewayConfig;
import veil.models.ProviderConfig;
import veil.models.ProviderType;

/**
 * Screen C: Integrations — configure AI providers, store/delete encrypted credentials,
 * run genuine connection tests, and view honest client integration instructions.
 */
public class IntegrationsView extends VBox {

    public IntegrationsView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(22);

        Label kicker = new Label("03 / PROVIDERS & INTEGRATIONS");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Integrations");
        title.getStyleClass().add("display-heading");

        Label note = new Label("VEIL only protects applications explicitly routed through its local gateway or Java SDK. Displaying setup instructions does not mean a client is connected.");
        note.getStyleClass().add("body-muted");
        note.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        GatewayConfig gwCfg = core.getConfigManager().getConfig().getGateway();

        VBox providerPanel = new VBox(14);
        providerPanel.getStyleClass().add("editorial-panel");

        Label provKicker = new Label("PROVIDER ADAPTER CONFIGURATION");
        provKicker.getStyleClass().add("section-kicker");

        ComboBox<ProviderType> providerCombo = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerCombo.setValue(gwCfg.getActiveProvider());

        TextField baseUrlField = new TextField();
        TextField modelField = new TextField();
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("Enter new API key (never displayed in full after saving)");
        Label storedKeyLabel = new Label();
        storedKeyLabel.getStyleClass().add("mono-text");
        Label routingNote = new Label();
        Label testStatus = new Label("Connection status: Not tested in this session.");
        testStatus.getStyleClass().add("body-muted");
        testStatus.setWrapText(true);

        Runnable loadProviderFields = () -> {
            ProviderType sel = providerCombo.getValue();
            ProviderConfig pc = core.getConfigManager().getConfig().getProviderConfig(sel);
            baseUrlField.setText(pc.getBaseUrl());
            modelField.setText(pc.getDefaultModel());
            apiKeyField.clear();
            storedKeyLabel.setText("Stored key: " + core.getCredentialStore().getMaskedDisplay(sel));
            routingNote.getStyleClass().removeAll("status-local", "body-muted");
            if (sel.isLocalOnly()) {
                routingNote.setText("LOCAL ROUTING: Requests stay on your machine via Ollama (no external cloud transmission).");
                routingNote.getStyleClass().add("status-local");
            } else {
                routingNote.setText("EXTERNAL CLOUD PROVIDER: Sanitized requests are transmitted to " + pc.getBaseUrl());
                routingNote.getStyleClass().add("body-muted");
            }
        };

        providerCombo.setOnAction(e -> loadProviderFields.run());
        loadProviderFields.run();

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);
        grid.add(new Label("Provider"), 0, 0);
        grid.add(providerCombo, 1, 0);
        grid.add(new Label("Base URL"), 0, 1);
        grid.add(baseUrlField, 1, 1);
        grid.add(new Label("Default Model"), 0, 2);
        grid.add(modelField, 1, 2);
        grid.add(new Label("API Credential"), 0, 3);
        grid.add(apiKeyField, 1, 3);
        grid.add(new Label("Saved Status"), 0, 4);
        grid.add(storedKeyLabel, 1, 4);

        Button saveProviderBtn = new Button("Save & Set Active Provider");
        saveProviderBtn.getStyleClass().add("btn-primary");
        saveProviderBtn.setOnAction(e -> {
            try {
                ProviderType sel = providerCombo.getValue();
                ProviderConfig pc = core.getConfigManager().getConfig().getProviderConfig(sel);
                pc.setBaseUrl(baseUrlField.getText().trim());
                pc.setDefaultModel(modelField.getText().trim());
                gwCfg.setActiveProvider(sel);
                core.getConfigManager().save();
                if (!apiKeyField.getText().isBlank()) {
                    core.getCredentialStore().saveKey(sel, apiKeyField.getText().trim());
                }
                loadProviderFields.run();
                testStatus.setText("Saved configuration for " + sel.getDisplayName() + " and set as active gateway provider.");
            } catch (Exception ex) {
                testStatus.setText("Failed to save provider settings: " + ex.getMessage());
            }
        });

        Button deleteKeyBtn = new Button("Delete Stored Key");
        deleteKeyBtn.getStyleClass().add("btn-danger");
        deleteKeyBtn.setOnAction(e -> {
            try {
                ProviderType sel = providerCombo.getValue();
                core.getCredentialStore().deleteKey(sel);
                loadProviderFields.run();
                testStatus.setText("Deleted stored API key for " + sel.getDisplayName() + ".");
            } catch (Exception ex) {
                testStatus.setText("Failed to delete key: " + ex.getMessage());
            }
        });

        Button testConnBtn = new Button("Test Connection");
        testConnBtn.getStyleClass().add("btn-secondary");
        testConnBtn.setOnAction(e -> {
            ProviderType sel = providerCombo.getValue();
            testStatus.setText("Testing live connection to " + sel.getDisplayName() + "...");
            new Thread(() -> {
                try {
                    boolean ok = core.testProviderConnection(sel);
                    Platform.runLater(() -> testStatus.setText(ok
                            ? "Verified connection succeeded for " + sel.getDisplayName() + "."
                            : "Connection check returned negative."));
                } catch (Exception ex) {
                    Platform.runLater(() -> testStatus.setText("Connection failed: " + ex.getMessage()));
                }
            }).start();
        });

        providerPanel.getChildren().addAll(
                provKicker, grid, routingNote,
                new HBox(10, saveProviderBtn, testConnBtn, deleteKeyBtn),
                testStatus
        );

        // Integration guides
        Label guideKicker = new Label("SUPPORTED CLIENT INTEGRATIONS & CONTRACTS");
        guideKicker.getStyleClass().add("section-kicker");

        VBox guides = new VBox(12);
        for (IntegrationDescriptor d : IntegrationDescriptor.buildDescriptors(gwCfg)) {
            VBox box = new VBox(6);
            box.getStyleClass().add("editorial-panel");
            Label name = new Label(d.name() + "  —  [" + d.status() + "]");
            name.getStyleClass().add("section-title");
            Label instr = new Label(d.instructions());
            instr.getStyleClass().add("body-muted");
            instr.setWrapText(true);
            TextArea snippet = new TextArea(d.snippet());
            snippet.setEditable(false);
            snippet.setPrefRowCount(3);
            snippet.getStyleClass().add("mono-text");
            box.getChildren().addAll(name, instr, snippet);
            guides.getChildren().add(box);
        }

        getChildren().addAll(kicker, title, note, div, providerPanel, guideKicker, guides);
    }
}
