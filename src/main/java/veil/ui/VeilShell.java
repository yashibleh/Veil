package veil.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.gateway.LocalApiGateway;
import veil.ui.views.ActivityView;
import veil.ui.views.AnalyticsView;
import veil.ui.views.GatewayView;
import veil.ui.views.IntegrationsView;
import veil.ui.views.OverviewView;
import veil.ui.views.PlaygroundView;
import veil.ui.views.ProfilesView;
import veil.ui.views.SettingsView;
import veil.ui.views.VaultView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Primary editorial shell hosting the asymmetric left rail, notification banner,
 * and animated screen stage for all 9 VEIL Control Centre screens.
 */
public class VeilShell extends BorderPane {

    private final VeilCoreEngine core;
    private final LocalApiGateway gateway;
    private final NotificationBanner notificationBanner = new NotificationBanner();
    private final ScrollPane stageScroll = new ScrollPane();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, Supplier<Node>> viewFactories = new LinkedHashMap<>();
    private final Label gatewayStatusPill = new Label();
    private final Label gatewayAddrPill = new Label();
    private String currentKey = "overview";

    public VeilShell(VeilCoreEngine core, LocalApiGateway gateway) {
        this.core = core;
        this.gateway = gateway;

        registerViews();
        setLeft(buildNavigationRail());

        VBox centerWrapper = new VBox();
        stageScroll.setFitToWidth(true);
        stageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(stageScroll, Priority.ALWAYS);
        centerWrapper.getChildren().addAll(notificationBanner, stageScroll);
        setCenter(centerWrapper);

        updateGatewayIndicator();
        navigateTo("overview");
    }

    private void registerViews() {
        viewFactories.put("overview", () -> new OverviewView(core, gateway, this::navigateTo));
        viewFactories.put("gateway", () -> new GatewayView(core, gateway, this::updateGatewayIndicator));
        viewFactories.put("integrations", () -> new IntegrationsView(core));
        viewFactories.put("profiles", () -> new ProfilesView(core));
        viewFactories.put("activity", () -> new ActivityView(core));
        viewFactories.put("vault", () -> new VaultView(core));
        viewFactories.put("analytics", () -> new AnalyticsView(core));
        viewFactories.put("playground", () -> new PlaygroundView(core));
        viewFactories.put("settings", () -> new SettingsView(core));
    }

    private VBox buildNavigationRail() {
        VBox rail = new VBox(6);
        rail.getStyleClass().add("nav-rail");
        rail.setPrefWidth(244);

        Label brand = new Label("VEIL");
        brand.getStyleClass().add("brand-title");
        Label sub = new Label("LOCAL PRIVACY GATEWAY");
        sub.getStyleClass().add("brand-subtitle");

        VBox brandBox = new VBox(2, brand, sub);
        brandBox.setPadding(new Insets(0, 0, 22, 0));
        rail.getChildren().add(brandBox);

        addNavItem(rail, "overview", "01  Overview");
        addNavItem(rail, "gateway", "02  Gateway");
        addNavItem(rail, "integrations", "03  Integrations");
        addNavItem(rail, "profiles", "04  Privacy Profiles");
        addNavItem(rail, "activity", "05  Activity Monitor");
        addNavItem(rail, "vault", "06  Token Vault");
        addNavItem(rail, "analytics", "07  Analytics");
        addNavItem(rail, "playground", "08  Prompt Playground");
        addNavItem(rail, "settings", "09  Settings");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Region divider = new Region();
        divider.getStyleClass().add("hairline-divider");

        VBox statusBox = new VBox(4, gatewayStatusPill, gatewayAddrPill);
        statusBox.setPadding(new Insets(14, 0, 0, 0));
        gatewayAddrPill.getStyleClass().add("mono-text");

        rail.getChildren().addAll(spacer, divider, statusBox);
        return rail;
    }

    private void addNavItem(VBox rail, String key, String label) {
        Button btn = new Button(label);
        btn.getStyleClass().add("nav-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> navigateTo(key));
        navButtons.put(key, btn);
        rail.getChildren().add(btn);
    }

    public void navigateTo(String key) {
        if (!viewFactories.containsKey(key)) {
            return;
        }
        this.currentKey = key;
        navButtons.forEach((k, btn) -> {
            btn.getStyleClass().remove("nav-item-active");
            if (k.equals(key)) {
                btn.getStyleClass().add("nav-item-active");
            }
        });
        Node view = viewFactories.get(key).get();
        stageScroll.setContent(view);
        MotionHelper.revealView(view, core.getConfigManager().getConfig().isReducedMotion());
    }

    public void refreshActiveView() {
        if ("overview".equals(currentKey) || "activity".equals(currentKey) || "analytics".equals(currentKey) || "vault".equals(currentKey)) {
            navigateTo(currentKey);
        }
    }

    public void updateGatewayIndicator() {
        boolean running = gateway.isRunning();
        gatewayStatusPill.getStyleClass().removeAll("status-running", "status-stopped");
        if (running) {
            gatewayStatusPill.setText("● GATEWAY ACTIVE");
            gatewayStatusPill.getStyleClass().add("status-running");
        } else {
            gatewayStatusPill.setText("○ GATEWAY STOPPED");
            gatewayStatusPill.getStyleClass().add("status-stopped");
        }
        var cfg = core.getConfigManager().getConfig().getGateway();
        gatewayAddrPill.setText(cfg.getBindAddress() + ":" + cfg.getPort());
    }

    public void showNotification(String level, String title, String message) {
        notificationBanner.showNotification(level, title, message);
    }
}
