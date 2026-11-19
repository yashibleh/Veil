package veil.ui.views;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.detection.Detector;
import veil.models.VeilConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Screen I: Settings — detector toggles, vault TTL, log retention, motion preferences,
 * background tray behavior, and data management.
 */
public class SettingsView extends VBox {

    public SettingsView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(20);

        Label kicker = new Label("09 / PREFERENCES");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Settings");
        title.getStyleClass().add("display-heading");

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        VeilConfig cfg = core.getConfigManager().getConfig();
        Label feedback = new Label();
        feedback.getStyleClass().add("body-muted");

        // Detector toggles
        VBox detPanel = new VBox(10);
        detPanel.getStyleClass().add("editorial-panel");
        Label detKicker = new Label("DETECTION ENGINE MODULES");
        detKicker.getStyleClass().add("section-kicker");
        detPanel.getChildren().add(detKicker);

        Map<String, CheckBox> detectorBoxes = new LinkedHashMap<>();
        for (Detector d : core.getDetectionEngine().getDetectors()) {
            CheckBox cb = new CheckBox(d.getName() + " — " + d.getDescription());
            cb.setSelected(core.getDetectionEngine().isDetectorEnabled(d.getName()));
            cb.setWrapText(true);
            detectorBoxes.put(d.getName(), cb);
            detPanel.getChildren().add(cb);
        }

        // Behavior, Storage & Motion
        VBox prefPanel = new VBox(12);
        prefPanel.getStyleClass().add("editorial-panel");
        Label prefKicker = new Label("VAULT, RETENTION & INTERFACE");
        prefKicker.getStyleClass().add("section-kicker");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);

        TextField ttlField = new TextField(String.valueOf(cfg.getSessionTtlMinutes()));
        TextField retentionField = new TextField(String.valueOf(cfg.getLogRetentionDays()));
        CheckBox reducedMotion = new CheckBox("Reduced motion (disable screen & chart transitions)");
        reducedMotion.setSelected(cfg.isReducedMotion());
        CheckBox minToTray = new CheckBox("Minimize to system tray on window close (background protection)");
        minToTray.setSelected(cfg.isMinimizeToTray());
        CheckBox desktopNotif = new CheckBox("Show desktop notifications for real gateway & policy events");
        desktopNotif.setSelected(cfg.isDesktopNotifications());

        grid.add(new Label("Session Vault TTL (minutes)"), 0, 0);
        grid.add(ttlField, 1, 0);
        grid.add(new Label("Activity Log Retention (days)"), 0, 1);
        grid.add(retentionField, 1, 1);

        Button saveBtn = new Button("Save All Settings");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            try {
                Map<String, Boolean> states = new LinkedHashMap<>();
                detectorBoxes.forEach((name, cb) -> states.put(name, cb.isSelected()));
                cfg.setEnabledDetectors(states);
                cfg.setSessionTtlMinutes(Integer.parseInt(ttlField.getText().trim()));
                cfg.setLogRetentionDays(Integer.parseInt(retentionField.getText().trim()));
                cfg.setReducedMotion(reducedMotion.isSelected());
                cfg.setMinimizeToTray(minToTray.isSelected());
                cfg.setDesktopNotifications(desktopNotif.isSelected());
                core.getConfigManager().save();
                core.applyConfigToRuntime();
                feedback.setText("Saved all settings and applied to active engine.");
            } catch (Exception ex) {
                feedback.setText("Invalid setting value: " + ex.getMessage());
            }
        });

        prefPanel.getChildren().addAll(prefKicker, grid, reducedMotion, minToTray, desktopNotif, saveBtn, feedback);

        getChildren().addAll(kicker, title, div, detPanel, prefPanel);
    }
}
