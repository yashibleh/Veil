package veil.app;

import javafx.application.Platform;
import javafx.stage.Stage;
import veil.gateway.LocalApiGateway;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Native desktop system tray integration allowing VEIL to run quietly in the background.
 * Degrades gracefully on headless or unsupported window managers.
 */
public class TrayManager {

    private final Stage primaryStage;
    private final LocalApiGateway gateway;
    private final Runnable onExit;
    private TrayIcon trayIcon;
    private MenuItem statusItem;

    public TrayManager(Stage primaryStage, LocalApiGateway gateway, Runnable onExit) {
        this.primaryStage = primaryStage;
        this.gateway = gateway;
        this.onExit = onExit;
    }

    public boolean install() {
        if (!SystemTray.isSupported()) {
            return false;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();

            statusItem = new MenuItem(gateway.isRunning() ? "Gateway: Running" : "Gateway: Stopped");
            statusItem.setEnabled(false);

            MenuItem openItem = new MenuItem("Open VEIL Control Centre");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            }));

            MenuItem startItem = new MenuItem("Start Gateway");
            startItem.addActionListener(e -> new Thread(() -> {
                try {
                    gateway.start();
                    updateStatus(true);
                } catch (Exception ignored) {
                }
            }).start());

            MenuItem stopItem = new MenuItem("Stop Gateway");
            stopItem.addActionListener(e -> {
                gateway.stop();
                updateStatus(false);
            });

            MenuItem quitItem = new MenuItem("Quit VEIL");
            quitItem.addActionListener(e -> {
                uninstall();
                Platform.runLater(onExit);
            });

            menu.add(statusItem);
            menu.addSeparator();
            menu.add(openItem);
            menu.add(startItem);
            menu.add(stopItem);
            menu.addSeparator();
            menu.add(quitItem);

            trayIcon = new TrayIcon(createTrayImage(), "VEIL Privacy Gateway", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            }));
            tray.add(trayIcon);
            return true;
        } catch (AWTException e) {
            return false;
        }
    }

    public void updateStatus(boolean running) {
        if (statusItem != null) {
            statusItem.setLabel(running ? "Gateway: Running" : "Gateway: Stopped");
        }
    }

    public void showBalloon(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    public void uninstall() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    private Image createTrayImage() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(16, 13, 24));
        g.fillRoundRect(0, 0, 16, 16, 4, 4);
        g.setColor(new Color(196, 181, 253));
        g.drawPolygon(new int[]{3, 8, 13}, new int[]{4, 13, 4}, 3);
        g.dispose();
        return img;
    }
}
