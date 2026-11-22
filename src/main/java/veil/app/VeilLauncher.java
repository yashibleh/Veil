package veil.app;

import veil.core.PrivacyEventListener;
import veil.core.VeilCoreEngine;
import veil.gateway.LocalApiGateway;
import veil.models.RequestEvent;
import veil.web.ControlServer;

import java.awt.TrayIcon;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VEIL entry point. Starts the privacy engine, the OpenAI-compatible gateway and the loopback
 * control surface, then opens the glass interface in an app-mode browser window.
 *
 * Flags: --no-window (headless / print URL only), --autostart (wake the gateway immediately).
 */
public final class VeilLauncher {

    private VeilLauncher() {
    }

    public static void main(String[] args) throws Exception {
        List<String> flags = Arrays.asList(args);
        Path home = Path.of(System.getProperty("user.home"), ".veil");

        VeilCoreEngine core = new VeilCoreEngine(home);
        LocalApiGateway gateway = new LocalApiGateway(core);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean quitting = new AtomicBoolean(false);

        WebTray[] trayRef = new WebTray[1];
        ControlServer[] uiRef = new ControlServer[1];

        Runnable quit = () -> {
            if (!quitting.compareAndSet(false, true)) {
                return;
            }
            gateway.stop();
            if (uiRef[0] != null) {
                uiRef[0].stop();
            }
            if (trayRef[0] != null) {
                trayRef[0].uninstall();
            }
            done.countDown();
        };

        ControlServer ui = new ControlServer(core, gateway, quit);
        uiRef[0] = ui;
        ui.start();

        Runnable openWindow = () -> {
            if (!AppWindow.open(ui.getLaunchUrl(), home.resolve("ui-profile"))) {
                System.out.println("[VEIL] Could not open a window automatically. Open this address in a Chromium browser:");
                System.out.println("       " + ui.getLaunchUrl());
            }
        };

        WebTray tray = new WebTray(gateway, openWindow, quit);
        trayRef[0] = tray;
        boolean trayInstalled = tray.install();

        core.addListener(new PrivacyEventListener() {
            @Override
            public void onRequestEvent(RequestEvent event) {
            }

            @Override
            public void onGatewayStateChanged(boolean running, String message) {
                tray.updateStatus(running);
            }

            @Override
            public void onNotification(String level, String title, String message) {
                if (trayInstalled && core.getConfigManager().getConfig().isDesktopNotifications()) {
                    TrayIcon.MessageType type = switch (level) {
                        case "ERROR" -> TrayIcon.MessageType.ERROR;
                        case "WARN" -> TrayIcon.MessageType.WARNING;
                        default -> TrayIcon.MessageType.INFO;
                    };
                    tray.balloon(title, message, type);
                }
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gateway.stop();
            ui.stop();
        }));

        if (flags.contains("--autostart")) {
            try {
                gateway.start();
            } catch (Exception e) {
                System.err.println("[VEIL] Gateway did not start: " + e.getMessage());
            }
        }

        System.out.println("[VEIL] Interface on http://127.0.0.1:" + ui.getPort() + "  (private launch token is in the window URL)");
        if (trayInstalled) {
            System.out.println("[VEIL] Running in the system tray. Close the window freely; the gateway keeps going.");
        }
        if (!flags.contains("--no-window")) {
            openWindow.run();
        } else {
            System.out.println("[VEIL] --no-window: " + ui.getLaunchUrl());
        }

        done.await();
        System.exit(0);
    }
}
