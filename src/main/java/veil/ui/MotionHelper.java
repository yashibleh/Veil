package veil.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Purposeful, restrained JavaFX motion helpers honoring the user's reduced-motion setting.
 */
public final class MotionHelper {

    private MotionHelper() {
    }

    public static void revealView(Node node, boolean reducedMotion) {
        if (node == null) {
            return;
        }
        if (reducedMotion) {
            node.setOpacity(1.0);
            node.setTranslateY(0);
            return;
        }
        node.setOpacity(0.0);
        node.setTranslateY(8);

        FadeTransition fade = new FadeTransition(Duration.millis(210), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        TranslateTransition slide = new TranslateTransition(Duration.millis(210), node);
        slide.setFromY(8);
        slide.setToY(0);

        new ParallelTransition(fade, slide).play();
    }
}
