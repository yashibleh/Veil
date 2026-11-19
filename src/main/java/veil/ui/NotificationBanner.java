package veil.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Non-intrusive editorial notification strip triggered only by real application events.
 */
public class NotificationBanner extends HBox {

    private final Label levelLabel = new Label();
    private final Label messageLabel = new Label();
    private PauseTransition hideTimer;

    public NotificationBanner() {
        getStyleClass().add("notification-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        setVisible(false);
        setManaged(false);

        levelLabel.getStyleClass().add("mono-text");
        messageLabel.getStyleClass().add("body-muted");
        messageLabel.setWrapText(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button dismiss = new Button("Dismiss");
        dismiss.getStyleClass().add("btn-secondary");
        dismiss.setOnAction(e -> hide());

        getChildren().addAll(levelLabel, messageLabel, spacer, dismiss);
    }

    public void showNotification(String level, String title, String message) {
        levelLabel.setText("[" + (level == null ? "INFO" : level) + "] " + title);
        messageLabel.setText(message == null ? "" : message);
        setVisible(true);
        setManaged(true);

        if (hideTimer != null) {
            hideTimer.stop();
        }
        hideTimer = new PauseTransition(Duration.seconds(6));
        hideTimer.setOnFinished(e -> hide());
        hideTimer.play();
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
    }
}
