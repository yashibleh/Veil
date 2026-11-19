package veil.ui.views;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.models.CustomRule;
import veil.models.EntityCategory;
import veil.models.PolicyAction;
import veil.models.PrivacyProfile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screen D: Privacy Profiles — genuine configuration editor for built-in and custom profiles,
 * category toggles, per-category policies, code identifiers, and custom detection rules.
 */
public class ProfilesView extends VBox {

    private final VeilCoreEngine core;
    private final ComboBox<PrivacyProfile> profileCombo = new ComboBox<>();
    private final Map<EntityCategory, CheckBox> categoryChecks = new EnumMap<>(EntityCategory.class);
    private final Map<EntityCategory, ComboBox<PolicyAction>> policyCombos = new EnumMap<>(EntityCategory.class);
    private final TextField codeIdsField = new TextField();
    private final TextField customRuleNameField = new TextField();
    private final TextField customRulePatternField = new TextField();
    private final CheckBox customRuleRegexCheck = new CheckBox("Regex");
    private final Label statusLabel = new Label();
    private final VBox rulesListBox = new VBox(6);

    public ProfilesView(VeilCoreEngine core) {
        this.core = core;
        getStyleClass().add("content-stage");
        setSpacing(20);

        Label kicker = new Label("04 / POLICY & PROFILES");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Privacy Profiles");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("Configure detection categories, enforcement actions (MASK, BLOCK, WARN, REALISTIC_FAKE, ALLOW), code identifiers, and custom rules.");
        desc.getStyleClass().add("body-muted");
        desc.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        // Profile selector bar
        refreshProfileList(core.getActiveProfile().getId());
        profileCombo.setOnAction(e -> populateEditor(profileCombo.getValue()));

        Button setActiveBtn = new Button("Activate Profile");
        setActiveBtn.getStyleClass().add("btn-primary");
        setActiveBtn.setOnAction(e -> {
            PrivacyProfile sel = profileCombo.getValue();
            if (sel != null) {
                core.getConfigManager().getConfig().getGateway().setActiveProfileId(sel.getId());
                core.getConfigManager().save();
                statusLabel.setText("Active gateway profile set to: " + sel.getName());
            }
        });

        Button dupBtn = new Button("Duplicate as Custom");
        dupBtn.getStyleClass().add("btn-secondary");
        dupBtn.setOnAction(e -> {
            PrivacyProfile sel = profileCombo.getValue();
            if (sel != null) {
                PrivacyProfile copy = core.getProfileManager().duplicateProfile(sel.getId(), sel.getName() + " (Custom)");
                refreshProfileList(copy.getId());
                statusLabel.setText("Created custom profile: " + copy.getName());
            }
        });

        Button deleteBtn = new Button("Delete Custom");
        deleteBtn.getStyleClass().add("btn-danger");
        deleteBtn.setOnAction(e -> {
            PrivacyProfile sel = profileCombo.getValue();
            if (sel != null && core.getProfileManager().deleteCustomProfile(sel.getId())) {
                refreshProfileList("developer");
                statusLabel.setText("Deleted custom profile.");
            } else {
                statusLabel.setText("Built-in profiles cannot be deleted (you can edit or duplicate them).");
            }
        });

        HBox selectorBar = new HBox(10, profileCombo, setActiveBtn, dupBtn, deleteBtn);

        // Category policy matrix
        VBox matrixPanel = new VBox(12);
        matrixPanel.getStyleClass().add("editorial-panel");
        Label matrixKicker = new Label("ENTITY CATEGORY POLICIES");
        matrixKicker.getStyleClass().add("section-kicker");

        GridPane matrix = new GridPane();
        matrix.setHgap(18);
        matrix.setVgap(8);

        int row = 0;
        for (EntityCategory cat : EntityCategory.values()) {
            CheckBox enabled = new CheckBox(cat.getDisplayName());
            ComboBox<PolicyAction> actionBox = new ComboBox<>(FXCollections.observableArrayList(PolicyAction.values()));
            categoryChecks.put(cat, enabled);
            policyCombos.put(cat, actionBox);
            matrix.add(enabled, 0, row);
            matrix.add(actionBox, 1, row);
            row++;
        }

        // Code identifiers
        Label codeLbl = new Label("Configured Code Identifiers (comma-separated)");
        codeLbl.getStyleClass().add("metric-label");
        codeIdsField.setPromptText("e.g., INTERNAL_SIGNING_SECRET, PROD_DB_CLUSTER");

        Button saveProfileBtn = new Button("Save Profile Changes");
        saveProfileBtn.getStyleClass().add("btn-primary");
        saveProfileBtn.setOnAction(e -> saveCurrentProfile());

        matrixPanel.getChildren().addAll(matrixKicker, matrix, codeLbl, codeIdsField, saveProfileBtn);

        // Custom rules editor
        VBox rulesPanel = new VBox(10);
        rulesPanel.getStyleClass().add("editorial-panel");
        Label rulesKicker = new Label("CUSTOM DETECTION RULES");
        rulesKicker.getStyleClass().add("section-kicker");

        customRuleNameField.setPromptText("Rule name (e.g., Project Apollo)");
        customRulePatternField.setPromptText("Literal term or regex pattern");
        Button addRuleBtn = new Button("Add Rule");
        addRuleBtn.getStyleClass().add("btn-secondary");
        addRuleBtn.setOnAction(e -> {
            PrivacyProfile sel = profileCombo.getValue();
            if (sel != null && !customRulePatternField.getText().isBlank()) {
                CustomRule r = new CustomRule(
                        customRuleNameField.getText().isBlank() ? "Custom Rule" : customRuleNameField.getText().trim(),
                        customRulePatternField.getText().trim(),
                        customRuleRegexCheck.isSelected(),
                        false,
                        EntityCategory.CUSTOM
                );
                sel.getCustomRules().add(r);
                core.getProfileManager().saveProfile(sel);
                customRuleNameField.clear();
                customRulePatternField.clear();
                populateEditor(sel);
                statusLabel.setText("Added custom rule to " + sel.getName());
            }
        });

        HBox addRuleRow = new HBox(10, customRuleNameField, customRulePatternField, customRuleRegexCheck, addRuleBtn);
        rulesPanel.getChildren().addAll(rulesKicker, addRuleRow, rulesListBox);

        statusLabel.getStyleClass().add("body-muted");
        populateEditor(profileCombo.getValue());

        getChildren().addAll(kicker, title, desc, div, selectorBar, statusLabel, matrixPanel, rulesPanel);
    }

    private void refreshProfileList(String selectId) {
        List<PrivacyProfile> all = new ArrayList<>(core.getProfileManager().getAllProfiles());
        profileCombo.setItems(FXCollections.observableArrayList(all));
        PrivacyProfile match = all.stream().filter(p -> p.getId().equals(selectId)).findFirst()
                .orElse(all.isEmpty() ? null : all.get(0));
        profileCombo.setValue(match);
    }

    private void populateEditor(PrivacyProfile p) {
        if (p == null) return;
        for (EntityCategory cat : EntityCategory.values()) {
            categoryChecks.get(cat).setSelected(p.getEnabledCategories().contains(cat));
            policyCombos.get(cat).setValue(p.getCategoryPolicies().getOrDefault(cat, PolicyAction.MASK));
        }
        codeIdsField.setText(String.join(", ", p.getCodeIdentifiers()));

        rulesListBox.getChildren().clear();
        if (p.getCustomRules().isEmpty()) {
            Label none = new Label("No custom rules defined on this profile.");
            none.getStyleClass().add("body-muted");
            rulesListBox.getChildren().add(none);
        } else {
            for (CustomRule r : p.getCustomRules()) {
                Label lbl = new Label("• " + r.getName() + "  [" + (r.isRegex() ? "REGEX" : "LITERAL") + "]  " + r.getPattern());
                lbl.getStyleClass().add("mono-text");
                rulesListBox.getChildren().add(lbl);
            }
        }
    }

    private void saveCurrentProfile() {
        PrivacyProfile p = profileCombo.getValue();
        if (p == null) return;
        Set<EntityCategory> enabled = EnumSet.noneOf(EntityCategory.class);
        Map<EntityCategory, PolicyAction> policies = new EnumMap<>(EntityCategory.class);
        for (EntityCategory cat : EntityCategory.values()) {
            if (categoryChecks.get(cat).isSelected()) {
                enabled.add(cat);
            }
            policies.put(cat, policyCombos.get(cat).getValue());
        }
        p.setEnabledCategories(enabled);
        p.setCategoryPolicies(policies);

        List<String> ids = new ArrayList<>();
        for (String part : codeIdsField.getText().split(",")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        p.setCodeIdentifiers(ids);
        core.getProfileManager().saveProfile(p);
        statusLabel.setText("Saved policy changes to profile: " + p.getName());
    }
}
