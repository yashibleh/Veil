package veil.app;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the VEIL interface in a chromeless "app mode" window using an installed Chromium-based
 * browser (Edge ships with Windows 11, so this normally just works). Falls back to the default
 * browser. A dedicated profile directory keeps VEIL's UI cookies away from everyday browsing.
 */
public final class AppWindow {

    private AppWindow() {
    }

    public static boolean open(String url, Path profileDir) {
        for (String exe : candidates()) {
            try {
                Files.createDirectories(profileDir);
                new ProcessBuilder(exe,
                        "--app=" + url,
                        "--user-data-dir=" + profileDir.toAbsolutePath(),
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--window-size=1360,860")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return true;
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String env : new String[]{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base == null) {
                    continue;
                }
                addIfExists(out, base + "\\Microsoft\\Edge\\Application\\msedge.exe");
                addIfExists(out, base + "\\Google\\Chrome\\Application\\chrome.exe");
                addIfExists(out, base + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            }
        } else if (os.contains("mac")) {
            addIfExists(out, "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            addIfExists(out, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            addIfExists(out, "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
        } else {
            for (String name : new String[]{"microsoft-edge", "microsoft-edge-stable", "google-chrome",
                    "google-chrome-stable", "chromium", "chromium-browser", "brave-browser"}) {
                out.add(name);
            }
        }
        return out;
    }

    private static void addIfExists(List<String> list, String path) {
        if (new File(path).isFile()) {
            list.add(path);
        }
    }
}
