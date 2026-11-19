package veil.app;

/**
 * Non-Application launcher class required so JavaFX starts reliably
 * from both Maven (`mvn javafx:run`) and shaded/fat JAR executions.
 */
public final class VeilLauncher {

    private VeilLauncher() {
    }

    public static void main(String[] args) {
        VeilApplication.launchApp(args);
    }
}
