package veil.ui.views;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.gateway.GatewayStatus;
import veil.gateway.LocalApiGateway;
import veil.models.GatewayConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Screen B: Gateway Management — start/stop/restart, port & access token control,
 * copy endpoint, and genuine HTTP health connectivity test.
 */
public class GatewayView extends VBox {

    public GatewayView(VeilCoreEngine core, LocalApiGateway gateway, Runnable onStatusChanged) {
        getStyleClass().add("content-stage");
        setSpacing(22);

        Label kicker = new Label("02 / LOCAL API GATEWAY");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Gateway Management");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("VEIL binds strictly to local loopback by default and exposes an OpenAI-compatible /v1/chat/completions endpoint.");
        desc.getStyleClass().add("body-muted");
        desc.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        GatewayConfig cfg = core.getConfigManager().getConfig().getGateway();
        GatewayStatus st = gateway.getStatus();

        // Status & Controls panel
        VBox statusPanel = new VBox(12);
        statusPanel.getStyleClass().add("editorial-panel");

        Label statusLabel = new Label(st.isRunning() ? "● RUNNING — " + st.getEndpointUrl() : "○ STOPPED — " + st.getEndpointUrl());
        statusLabel.getStyleClass().add(st.isRunning() ? "status-running" : "status-stopped");

        Label feedbackLabel = new Label(st.getLastError() == null ? "No gateway errors reported." : "Last error: " + st.getLastError());
        feedbackLabel.getStyleClass().add(st.getLastError() == null ? "body-muted" : "status-stopped");
        feedbackLabel.setWrapText(true);

        Button startBtn = new Button("Start Gateway");
        startBtn.getStyleClass().add("btn-primary");
        Button stopBtn = new Button("Stop Gateway");
        stopBtn.getStyleClass().add("btn-danger");
        Button restartBtn = new Button("Restart");
        restartBtn.getStyleClass().add("btn-secondary");
        Button copyUrlBtn = new Button("Copy Endpoint");
        copyUrlBtn.getStyleClass().add("btn-secondary");
        Button healthBtn = new Button("Test /health");
        healthBtn.getStyleClass().add("btn-secondary");

        Runnable refreshLabels = () -> {
            GatewayStatus cur = gateway.getStatus();
            statusLabel.getStyleClass().removeAll("status-running", "status-stopped");
            statusLabel.getStyleClass().add(cur.isRunning() ? "status-running" : "status-stopped");
            statusLabel.setText((cur.isRunning() ? "● RUNNING — " : "○ STOPPED — ") + cur.getEndpointUrl());
            if (cur.getLastError() != null) {
                feedbackLabel.setText("Last error: " + cur.getLastError());
            }
            onStatusChanged.run();
        };

        startBtn.setOnAction(e -> new Thread(() -> {
            try {
                gateway.start();
                Platform.runLater(() -> {
                    feedbackLabel.setText("Gateway started on " + gateway.getStatus().getEndpointUrl());
                    refreshLabels.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    feedbackLabel.setText("Start failed: " + ex.getMessage());
                    refreshLabels.run();
                });
            }
        }).start());

        stopBtn.setOnAction(e -> {
            gateway.stop();
            feedbackLabel.setText("Gateway stopped cleanly.");
            refreshLabels.run();
        });

        restartBtn.setOnAction(e -> new Thread(() -> {
            try {
                gateway.restart();
                Platform.runLater(() -> {
                    feedbackLabel.setText("Gateway restarted on " + gateway.getStatus().getEndpointUrl());
                    refreshLabels.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    feedbackLabel.setText("Restart failed: " + ex.getMessage());
                    refreshLabels.run();
                });
            }
        }).start());

        copyUrlBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(gateway.getStatus().getEndpointUrl());
            Clipboard.getSystemClipboard().setContent(cc);
            feedbackLabel.setText("Copied endpoint URL to clipboard.");
        });

        healthBtn.setOnAction(e -> new Thread(() -> {
            try {
                String healthUrl = "http://" + cfg.getBindAddress() + ":" + cfg.getPort() + "/health";
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(healthUrl)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                Platform.runLater(() -> feedbackLabel.setText("Health check HTTP " + resp.statusCode() + ": " + resp.body()));
            } catch (Exception ex) {
                Platform.runLater(() -> feedbackLabel.setText("Health check failed (is gateway running?): " + ex.getMessage()));
            }
        }).start());

        HBox btnRow = new HBox(10, startBtn, stopBtn, restartBtn, copyUrlBtn, healthBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        statusPanel.getChildren().addAll(statusLabel, btnRow, feedbackLabel);

        // Configuration form
        VBox configPanel = new VBox(14);
        configPanel.getStyleClass().add("editorial-panel");
        Label cfgTitle = new Label("ENDPOINT & ACCESS CONTROL CONFIGURATION");
        cfgTitle.getStyleClass().add("section-kicker");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);

        TextField portField = new TextField(String.valueOf(cfg.getPort()));
        TextField timeoutField = new TextField(String.valueOf(cfg.getRequestTimeoutSeconds()));
        CheckBox authCheck = new CheckBox("Require local Bearer token authentication (recommended)");
        authCheck.setSelected(cfg.isRequireLocalAuth());
        CheckBox streamCheck = new CheckBox("Allow Server-Sent Events (SSE) streaming responses");
        streamCheck.setSelected(cfg.isStreamingAllowed());

        Label tokenVal = new Label(maskToken(cfg.getGatewayAccessToken()));
        tokenVal.getStyleClass().add("mono-text");

        Button copyTokenBtn = new Button("Copy Local Token");
        copyTokenBtn.getStyleClass().add("btn-secondary");
        copyTokenBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(cfg.getGatewayAccessToken());
            Clipboard.getSystemClipboard().setContent(cc);
            feedbackLabel.setText("Copied local gateway access token to clipboard.");
        });

        Button rotateTokenBtn = new Button("Rotate Token");
        rotateTokenBtn.getStyleClass().add("btn-secondary");
        rotateTokenBtn.setOnAction(e -> {
            String next = core.getConfigManager().rotateGatewayToken();
            tokenVal.setText(maskToken(next));
            feedbackLabel.setText("Rotated local gateway access token. Update your client configuration.");
        });

        grid.add(new Label("Port (1024–65535)"), 0, 0);
        grid.add(portField, 1, 0);
        grid.add(new Label("Request Timeout (sec)"), 0, 1);
        grid.add(timeoutField, 1, 1);
        grid.add(new Label("Local Access Token"), 0, 2);
        grid.add(new HBox(10, tokenVal, copyTokenBtn, rotateTokenBtn), 1, 2);

        Button saveCfgBtn = new Button("Save Gateway Settings");
        saveCfgBtn.getStyleClass().add("btn-primary");
        saveCfgBtn.setOnAction(e -> {
            try {
                int p = Integer.parseInt(portField.getText().trim());
                int t = Integer.parseInt(timeoutField.getText().trim());
                cfg.setPort(p);
                cfg.setRequestTimeoutSeconds(t);
                cfg.setRequireLocalAuth(authCheck.isSelected());
                cfg.setStreamingAllowed(streamCheck.isSelected());
                core.getConfigManager().save();
                feedbackLabel.setText("Saved gateway configuration. Restart gateway if port changed.");
                refreshLabels.run();
            } catch (Exception ex) {
                feedbackLabel.setText("Invalid configuration: " + ex.getMessage());
            }
        });

        configPanel.getChildren().addAll(cfgTitle, grid, authCheck, streamCheck, saveCfgBtn);
        getChildren().addAll(kicker, title, desc, div, statusPanel, configPanel);
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 12) return "••••••••";
        return token.substring(0, 10) + "••••••••" + token.substring(token.length() - 4);
    }
}
