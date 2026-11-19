package veil.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import veil.core.PrivacyEventListener;
import veil.core.VeilCoreEngine;
import veil.gateway.LocalApiGateway;
import veil.models.DetectedEntity;
import veil.models.RequestEvent;
import veil.ui.VeilShell;

import java.awt.TrayIcon;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Main JavaFX Control Centre application for VEIL.
 * Wires the JavaFX dashboard to the independent VeilCoreEngine and LocalApiGateway.
 */
public class VeilApplication extends Application {

    private VeilCoreEngine coreEngine;
    private LocalApiGateway gateway;
    private TrayManager trayManager;

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Path homeDir = Path.of(System.getProperty("user.home"), ".veil");
        this.coreEngine = new VeilCoreEngine(homeDir);
        this.gateway = new LocalApiGateway(coreEngine);

        // Attach interactive WARN confirmation dialog on the JavaFX thread
        coreEngine.setWarnCallback((warnedEntities, profile) -> askUserWarnConfirmation(warnedEntities, profile.getName()));

        VeilShell shell = new VeilShell(coreEngine, gateway);
        Scene scene = new Scene(shell, 1280, 820);
        String css = getClass().getResource("/styles/veil.css").toExternalForm();
        scene.getStylesheets().add(css);

        stage.setTitle("VEIL — Local-First Privacy Gateway");
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
        stage.setScene(scene);

        Platform.setImplicitExit(false);
        this.trayManager = new TrayManager(stage, gateway, this::shutdownAndExit);
        boolean trayInstalled = trayManager.install();

        coreEngine.addListener(new PrivacyEventListener() {
            @Override
            public void onRequestEvent(RequestEvent event) {
                Platform.runLater(shell::refreshActiveView);
            }

            @Override
            public void onGatewayStateChanged(boolean running, String message) {
                trayManager.updateStatus(running);
                Platform.runLater(shell::updateGatewayIndicator);
            }

            @Override
            public void onNotification(String level, String title, String message) {
                Platform.runLater(() -> shell.showNotification(level, title, message));
                if (coreEngine.getConfigManager().getConfig().isDesktopNotifications() && trayInstalled) {
                    TrayIcon.MessageType type = switch (level) {
                        case "ERROR" -> TrayIcon.MessageType.ERROR;
                        case "WARN" -> TrayIcon.MessageType.WARNING;
                        default -> TrayIcon.MessageType.INFO;
                    };
                    trayManager.showBalloon(title, message, type);
                }
            }
        });

        stage.setOnCloseRequest(event -> {
            if (trayInstalled && coreEngine.getConfigManager().getConfig().isMinimizeToTray()) {
                event.consume();
                stage.hide();
                trayManager.showBalloon("VEIL Running in Background",
                        gateway.isRunning() ? "Local gateway remains active." : "Gateway is currently stopped.",
                        TrayIcon.MessageType.INFO);
            } else {
                shutdownAndExit();
            }
        });

        stage.show();
    }

    private boolean askUserWarnConfirmation(List<DetectedEntity> entities, String profileName) {
        if (Platform.isFxApplicationThread()) {
            return showWarnAlert(entities, profileName);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        Platform.runLater(() -> {
            try {
                approved.set(showWarnAlert(entities, profileName));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return approved.get();
    }

    private boolean showWarnAlert(List<DetectedEntity> entities, String profileName) {
        String categories = entities.stream()
                .map(e -> e.getCategory().getDisplayName())
                .distinct()
                .collect(Collectors.joining(", "));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("VEIL Privacy Warning");
        alert.setHeaderText("WARN Policy Triggered (" + profileName + ")");
        alert.setContentText("Detected " + entities.size() + " sensitive item(s) [" + categories
                + "]. Forward request with reversible masking applied?");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void shutdownAndExit() {
        if (gateway != null) {
            gateway.stop();
        }
        if (trayManager != null) {
            trayManager.uninstall();
        }
        Platform.exit();
        System.exit(0);
    }
}
