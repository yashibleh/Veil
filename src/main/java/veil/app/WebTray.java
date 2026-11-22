package veil.app;

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
 * System tray presence so the gateway can keep running after the interface window is closed.
 * Pure AWT, no JavaFX. Degrades silently on headless or unsupported desktops.
 */
public class WebTray {

    private final LocalApiGateway gateway;
    private final Runnable onOpen;
    private final Runnable onQuit;
    private TrayIcon trayIcon;
    private MenuItem statusItem;

    public WebTray(LocalApiGateway gateway, Runnable onOpen, Runnable onQuit) {
        this.gateway = gateway;
        this.onOpen = onOpen;
        this.onQuit = onQuit;
    }

    public boolean install() {
        if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return false;
        }
        try {
            PopupMenu menu = new PopupMenu();
            statusItem = new MenuItem(gateway.isRunning() ? "Veil: listening" : "Veil: resting");
            statusItem.setEnabled(false);

            MenuItem open = new MenuItem("Open VEIL");
            open.addActionListener(e -> onOpen.run());

            MenuItem start = new MenuItem("Wake the gateway");
            start.addActionListener(e -> new Thread(() -> {
                try {
                    gateway.start();
                } catch (Exception ignored) {
                }
            }).start());

            MenuItem stop = new MenuItem("Let it rest");
            stop.addActionListener(e -> gateway.stop());

            MenuItem quit = new MenuItem("Quit VEIL");
            quit.addActionListener(e -> new Thread(onQuit).start());

            menu.add(statusItem);
            menu.addSeparator();
            menu.add(open);
            menu.add(start);
            menu.add(stop);
            menu.addSeparator();
            menu.add(quit);

            trayIcon = new TrayIcon(icon(), "VEIL privacy gateway", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> onOpen.run());
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException | RuntimeException e) {
            return false;
        }
    }

    public void updateStatus(boolean running) {
        if (statusItem != null) {
            statusItem.setLabel(running ? "Veil: listening" : "Veil: resting");
        }
    }

    public void balloon(String title, String message, TrayIcon.MessageType type) {
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

    private Image icon() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(10, 10, 20));
        g.fillOval(1, 1, 30, 30);
        g.setColor(new Color(196, 181, 253));
        g.setStroke(new java.awt.BasicStroke(2.4f));
        g.drawOval(4, 4, 24, 24);
        g.setColor(new Color(255, 255, 255, 150));
        g.fillOval(9, 8, 7, 5);
        g.dispose();
        return img;
    }
}
