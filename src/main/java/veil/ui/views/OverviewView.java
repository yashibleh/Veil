package veil.ui.views;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.analytics.AnalyticsSnapshot;
import veil.core.VeilCoreEngine;
import veil.gateway.GatewayStatus;
import veil.gateway.LocalApiGateway;
import veil.models.GatewayConfig;
import veil.models.ProviderConfig;
import veil.models.RequestEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Screen A: Overview — immediate visibility into genuine gateway state,
 * active provider/profile, real request counts, and honest configuration warnings.
 */
public class OverviewView extends VBox {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public OverviewView(VeilCoreEngine core, LocalApiGateway gateway, Consumer<String> navigator) {
        getStyleClass().add("content-stage");
        setSpacing(24);

        // Editorial Header
        Label kicker = new Label("01 / CONTROL CENTRE");
        kicker.getStyleClass().add("section-kicker");

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.BASELINE_LEFT);
        Label h1 = new Label("Privacy in");
        h1.getStyleClass().add("display-heading");
        Label h1Accent = new Label("motion.");
        h1Accent.getStyleClass().add("display-italic");
        titleRow.getChildren().addAll(h1, h1Accent);

        Label subtitle = new Label("VEIL intercepts requests routed through its local gateway, enforces your policy profile, and restores original values on return.");
        subtitle.getStyleClass().add("body-muted");
        subtitle.setWrapText(true);

        Region rule1 = new Region();
        rule1.getStyleClass().add("hairline-divider");

        // Runtime Strip (Asymmetric editorial columns)
        GatewayStatus status = gateway.getStatus();
        GatewayConfig gwCfg = core.getConfigManager().getConfig().getGateway();
        ProviderConfig provCfg = core.getConfigManager().getConfig().getProviderConfig(gwCfg.getActiveProvider());

        HBox runtimeStrip = new HBox(28);
        runtimeStrip.getStyleClass().add("editorial-panel");
        runtimeStrip.setAlignment(Pos.CENTER_LEFT);

        VBox gwCol = new VBox(4);
        Label gwLbl = new Label("GATEWAY STATUS");
        gwLbl.getStyleClass().add("metric-label");
        Label gwState = new Label(status.isRunning() ? "RUNNING" : "STOPPED");
        gwState.getStyleClass().add(status.isRunning() ? "status-running" : "status-stopped");
        Label gwUrl = new Label(status.getEndpointUrl());
        gwUrl.getStyleClass().add("mono-text");
        gwCol.getChildren().addAll(gwLbl, gwState, gwUrl);

        VBox provCol = new VBox(4);
        Label provLbl = new Label("ROUTING PROVIDER");
        provLbl.getStyleClass().add("metric-label");
        Label provName = new Label(gwCfg.getActiveProvider().getDisplayName()
                + (gwCfg.getActiveProvider().isLocalOnly() ? "  [LOCAL]" : "  [CLOUD]"));
        provName.getStyleClass().add(gwCfg.getActiveProvider().isLocalOnly() ? "status-local" : "section-title");
        Label modelName = new Label("Model: " + provCfg.getDefaultModel());
        modelName.getStyleClass().add("mono-text");
        provCol.getChildren().addAll(provLbl, provName, modelName);

        VBox profCol = new VBox(4);
        Label profLbl = new Label("ACTIVE PROFILE");
        profLbl.getStyleClass().add("metric-label");
        Label profName = new Label(core.getActiveProfile().getName());
        profName.getStyleClass().add("section-title");
        Label vaultState = new Label("Vault: " + (core.getTokenVault().isLocked() ? "LOCKED" : "UNLOCKED"));
        vaultState.getStyleClass().add(core.getTokenVault().isLocked() ? "status-warn" : "mono-text");
        profCol.getChildren().addAll(profLbl, profName, vaultState);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button manageBtn = new Button(status.isRunning() ? "Manage Gateway" : "Start Gateway");
        manageBtn.getStyleClass().add("btn-primary");
        manageBtn.setOnAction(e -> navigator.accept("gateway"));

        runtimeStrip.getChildren().addAll(gwCol, provCol, profCol, spacer, manageBtn);

        // Genuine Metrics Row (strictly real numbers from AnalyticsService)
        AnalyticsSnapshot snap = core.getAnalyticsService().computeSnapshot();
        HBox metricsRow = new HBox(36);
        metricsRow.getChildren().addAll(
                buildMetric(String.valueOf(snap.getTotalRequests()), "REQUESTS PROCESSED"),
                buildMetric(String.valueOf(snap.getTotalEntitiesDetected()), "ENTITIES DETECTED"),
                buildMetric(String.valueOf(snap.getTotalEntitiesMasked()), "ENTITIES MASKED"),
                buildMetric(String.valueOf(snap.getBlockedRequests()), "REQUESTS BLOCKED")
        );

        // Configuration Warnings (honest checks only)
        List<String> warnings = new ArrayList<>();
        if (!status.isRunning()) {
            warnings.add("Local gateway is stopped. External AI tools configured for VEIL will not reach a provider until started.");
        }
        if (gwCfg.getActiveProvider().isRequiresApiKey() && !core.getCredentialStore().hasKey(gwCfg.getActiveProvider())) {
            warnings.add("No API credential configured for " + gwCfg.getActiveProvider().getDisplayName() + ". Configure one in Integrations.");
        }
        if (core.getTokenVault().isLocked()) {
            warnings.add("Token Vault is currently locked. Unlock it in Token Vault before sending requests.");
        }
        if (!gwCfg.isRequireLocalAuth()) {
            warnings.add("Local gateway Bearer authentication is disabled. Enable it in Gateway Management for stronger local access control.");
        }

        VBox warningsBox = new VBox(8);
        if (!warnings.isEmpty()) {
            Label warnTitle = new Label("CONFIGURATION ATTENTION");
            warnTitle.getStyleClass().add("status-warn");
            warningsBox.getStyleClass().add("editorial-panel");
            warningsBox.getChildren().add(warnTitle);
            for (String w : warnings) {
                Label wl = new Label("• " + w);
                wl.getStyleClass().add("body-muted");
                wl.setWrapText(true);
                warningsBox.getChildren().add(wl);
            }
        }

        // Recent Genuine Activity
        Label recentTitle = new Label("RECENT GENUINE ACTIVITY");
        recentTitle.getStyleClass().add("section-kicker");

        VBox activityList = new VBox(8);
        List<RequestEvent> recent = core.getAnalyticsService().getRecentEvents();
        if (recent.isEmpty()) {
            VBox empty = new VBox(6);
            empty.getStyleClass().add("empty-state");
            Label em1 = new Label("No requests processed yet.");
            em1.getStyleClass().add("section-title");
            Label em2 = new Label("Start the local gateway and point an OpenAI-compatible client to " + status.getEndpointUrl()
                    + ", or test a prompt in Prompt Playground.");
            em2.getStyleClass().add("body-muted");
            em2.setWrapText(true);
            empty.getChildren().addAll(em1, em2);
            activityList.getChildren().add(empty);
        } else {
            int limit = Math.min(6, recent.size());
            for (int i = 0; i < limit; i++) {
                RequestEvent ev = recent.get(i);
                HBox row = new HBox(16);
                row.getStyleClass().add("editorial-panel");
                row.setAlignment(Pos.CENTER_LEFT);
                Label ts = new Label(TIME_FMT.format(ev.getTimestamp()));
                ts.getStyleClass().add("mono-text");
                Label st = new Label(ev.getStatus().name());
                st.getStyleClass().add(ev.isBlocked() ? "status-stopped" : "status-running");
                Label detail = new Label((ev.getProvider() == null ? "—" : ev.getProvider().getDisplayName())
                        + " · " + ev.getDetectedCount() + " detected, " + ev.getMaskedCount() + " masked · "
                        + ev.getDurationMs() + " ms");
                detail.getStyleClass().add("body-muted");
                row.getChildren().addAll(ts, st, detail);
                activityList.getChildren().add(row);
            }
        }

        getChildren().addAll(kicker, titleRow, subtitle, rule1, runtimeStrip, metricsRow);
        if (!warnings.isEmpty()) {
            getChildren().add(warningsBox);
        }
        getChildren().addAll(recentTitle, activityList);
    }

    private VBox buildMetric(String value, String label) {
        VBox box = new VBox(2);
        Label val = new Label(value);
        val.getStyleClass().add("metric-value");
        Label lbl = new Label(label);
        lbl.getStyleClass().add("metric-label");
        box.getChildren().addAll(val, lbl);
        return box;
    }
}
