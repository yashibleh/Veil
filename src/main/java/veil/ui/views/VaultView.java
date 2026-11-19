package veil.ui.views;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.vault.TokenMapping;
import veil.vault.TokenVault;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen F: Token Vault — session-isolated mappings (hidden by default until explicitly revealed),
 * AES-256-GCM persistent storage toggle, lock/unlock, session clearing, and secure wipe.
 */
public class VaultView extends VBox {

    public VaultView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(20);

        Label kicker = new Label("06 / REVERSIBLE TOKEN VAULT");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Token Vault");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("Original values are hidden by default. Enable encrypted persistent storage (AES-256-GCM + PBKDF2) or keep mappings strictly in-memory.");
        desc.getStyleClass().add("body-muted");
        desc.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        TokenVault vault = core.getTokenVault();

        // Vault state & encryption panel
        VBox cryptoPanel = new VBox(12);
        cryptoPanel.getStyleClass().add("editorial-panel");

        Label stateLabel = new Label();
        Label feedbackLabel = new Label();
        feedbackLabel.getStyleClass().add("body-muted");

        PasswordField passphraseField = new PasswordField();
        passphraseField.setPromptText("Vault passphrase (min 8 chars for persistence / unlock)");
        passphraseField.setPrefWidth(320);

        Button enablePersistBtn = new Button("Enable Encrypted Persistence");
        enablePersistBtn.getStyleClass().add("btn-secondary");
        Button lockBtn = new Button("Lock Vault");
        lockBtn.getStyleClass().add("btn-secondary");
        Button unlockBtn = new Button("Unlock Vault");
        unlockBtn.getStyleClass().add("btn-primary");
        Button wipeBtn = new Button("Secure Wipe All");
        wipeBtn.getStyleClass().add("btn-danger");

        ComboBox<String> sessionCombo = new ComboBox<>();
        CheckBox revealCheck = new CheckBox("Reveal original sensitive values");
        revealCheck.setSelected(false);

        TableView<TokenMapping> table = new TableView<>();
        table.setPrefHeight(340);
        table.setPlaceholder(new Label("No token mappings available in the selected session."));

        TableColumn<TokenMapping, String> tokCol = new TableColumn<>("TOKEN / REPLACEMENT");
        tokCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getToken()));
        tokCol.setPrefWidth(210);

        TableColumn<TokenMapping, String> catCol = new TableColumn<>("CATEGORY");
        catCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getCategory().getDisplayName()));
        catCol.setPrefWidth(160);

        TableColumn<TokenMapping, String> actCol = new TableColumn<>("POLICY");
        actCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getAction().name()));
        actCol.setPrefWidth(130);

        TableColumn<TokenMapping, String> valCol = new TableColumn<>("ORIGINAL VALUE");
        valCol.setCellValueFactory(d -> new SimpleStringProperty(
                revealCheck.isSelected() ? d.getValue().getOriginalValue() : d.getValue().getRedactedPreview()
        ));
        valCol.setPrefWidth(280);

        table.getColumns().addAll(tokCol, catCol, actCol, valCol);

        Runnable refreshVaultUI = () -> {
            String mode = vault.isPersistentEnabled() ? "PERSISTENT (AES-256-GCM)" : "IN-MEMORY ONLY";
            String lockState = vault.isLocked() ? "LOCKED" : "UNLOCKED";
            stateLabel.setText("VAULT STATUS: " + lockState + "  ·  STORAGE: " + mode
                    + "  ·  ACTIVE SESSIONS: " + vault.getActiveSessionIds().size());
            stateLabel.getStyleClass().removeAll("status-running", "status-warn");
            stateLabel.getStyleClass().add(vault.isLocked() ? "status-warn" : "status-running");

            List<String> ids = new ArrayList<>(vault.getActiveSessionIds());
            String prev = sessionCombo.getValue();
            sessionCombo.setItems(FXCollections.observableArrayList(ids));
            if (prev != null && ids.contains(prev)) {
                sessionCombo.setValue(prev);
            } else if (!ids.isEmpty()) {
                sessionCombo.setValue(ids.get(0));
            } else {
                sessionCombo.setValue(null);
            }
            String sel = sessionCombo.getValue();
            table.setItems(FXCollections.observableArrayList(
                    sel == null ? List.of() : vault.getSessionMappings(sel)
            ));
        };

        sessionCombo.setOnAction(e -> {
            String sel = sessionCombo.getValue();
            table.setItems(FXCollections.observableArrayList(
                    sel == null ? List.of() : vault.getSessionMappings(sel)
            ));
        });

        revealCheck.setOnAction(e -> table.refresh());

        enablePersistBtn.setOnAction(e -> {
            try {
                vault.enablePersistence(passphraseField.getText().toCharArray());
                core.getConfigManager().getConfig().setPersistentVaultEnabled(true);
                core.getConfigManager().save();
                passphraseField.clear();
                feedbackLabel.setText("Encrypted persistent vault enabled at " + vault.getEncryptedVaultPath());
                refreshVaultUI.run();
            } catch (Exception ex) {
                feedbackLabel.setText("Persistence error: " + ex.getMessage());
            }
        });

        lockBtn.setOnAction(e -> {
            try {
                vault.lock();
                feedbackLabel.setText("Vault locked and in-memory mappings cleared.");
                core.notifyAll("WARN", "Vault Locked", "Token vault has been locked.");
                refreshVaultUI.run();
            } catch (Exception ex) {
                feedbackLabel.setText("Lock failed: " + ex.getMessage());
            }
        });

        unlockBtn.setOnAction(e -> {
            try {
                vault.unlock(passphraseField.getText().toCharArray());
                passphraseField.clear();
                feedbackLabel.setText("Vault unlocked successfully.");
                refreshVaultUI.run();
            } catch (Exception ex) {
                feedbackLabel.setText("Unlock failed (check passphrase): " + ex.getMessage());
            }
        });

        wipeBtn.setOnAction(e -> {
            vault.secureWipeAll();
            feedbackLabel.setText("All sessions and persisted vault data securely wiped.");
            refreshVaultUI.run();
        });

        Button clearSessionBtn = new Button("Clear Selected Session");
        clearSessionBtn.getStyleClass().add("btn-secondary");
        clearSessionBtn.setOnAction(e -> {
            String sel = sessionCombo.getValue();
            if (sel != null) {
                vault.clearSession(sel);
                feedbackLabel.setText("Cleared session: " + sel);
                refreshVaultUI.run();
            }
        });

        cryptoPanel.getChildren().addAll(
                stateLabel,
                new HBox(10, passphraseField, enablePersistBtn, lockBtn, unlockBtn, wipeBtn),
                feedbackLabel
        );

        HBox sessionBar = new HBox(12, new Label("Session:"), sessionCombo, clearSessionBtn, revealCheck);
        refreshVaultUI.run();

        getChildren().addAll(kicker, title, desc, div, cryptoPanel, sessionBar, table);
    }
}
