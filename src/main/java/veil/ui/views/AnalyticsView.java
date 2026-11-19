package veil.ui.views;

import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.analytics.AnalyticsSnapshot;
import veil.core.VeilCoreEngine;
import veil.models.EntityCategory;
import veil.models.ProviderType;

import java.util.Map;

/**
 * Screen G: Analytics — genuine statistics computed exclusively from real recorded events.
 * Displays an honest empty state when zero requests have been processed.
 */
public class AnalyticsView extends VBox {

    public AnalyticsView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(22);

        Label kicker = new Label("07 / GENUINE ANALYTICS");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Analytics");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("All statistics below are computed directly from real gateway, playground, and SDK events recorded in local SQLite storage.");
        desc.getStyleClass().add("body-muted");
        desc.setWrapText(true);

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        AnalyticsSnapshot snap = core.getAnalyticsService().computeSnapshot();
        getChildren().addAll(kicker, title, desc, div);

        if (snap.isEmpty()) {
            VBox empty = new VBox(8);
            empty.getStyleClass().add("empty-state");
            Label em1 = new Label("No analytics data available yet.");
            em1.getStyleClass().add("section-title");
            Label em2 = new Label("Process at least one request through the local gateway or Prompt Playground to populate genuine metrics.");
            em2.getStyleClass().add("body-muted");
            empty.getChildren().addAll(em1, em2);
            getChildren().add(empty);
            return;
        }

        HBox summaryRow = new HBox(34,
                metric(String.valueOf(snap.getTotalRequests()), "TOTAL REQUESTS"),
                metric(String.valueOf(snap.getForwardedRequests()), "FORWARDED"),
                metric(String.valueOf(snap.getBlockedRequests()), "BLOCKED"),
                metric(String.valueOf(snap.getTotalEntitiesDetected()), "DETECTED ENTITIES"),
                metric(String.valueOf(snap.getTotalEntitiesMasked()), "MASKED ENTITIES"),
                metric(String.format("%.1f ms", snap.getAverageDurationMs()), "AVG LATENCY")
        );

        // Entity Category Distribution Chart
        CategoryAxis catX = new CategoryAxis();
        NumberAxis catY = new NumberAxis();
        BarChart<String, Number> catChart = new BarChart<>(catX, catY);
        catChart.setTitle("Detected Entity Categories");
        catChart.setLegendVisible(false);
        catChart.setAnimated(!core.getConfigManager().getConfig().isReducedMotion());
        XYChart.Series<String, Number> catSeries = new XYChart.Series<>();
        for (Map.Entry<EntityCategory, Long> entry : snap.getCategoryDistribution().entrySet()) {
            catSeries.getData().add(new XYChart.Data<>(entry.getKey().getTokenPrefix(), entry.getValue()));
        }
        catChart.getData().add(catSeries);
        catChart.setPrefHeight(280);

        // Provider Usage Chart
        CategoryAxis provX = new CategoryAxis();
        NumberAxis provY = new NumberAxis();
        BarChart<String, Number> provChart = new BarChart<>(provX, provY);
        provChart.setTitle("Provider Routing Distribution");
        provChart.setLegendVisible(false);
        provChart.setAnimated(!core.getConfigManager().getConfig().isReducedMotion());
        XYChart.Series<String, Number> provSeries = new XYChart.Series<>();
        for (Map.Entry<ProviderType, Long> entry : snap.getProviderUsage().entrySet()) {
            provSeries.getData().add(new XYChart.Data<>(entry.getKey().getDisplayName(), entry.getValue()));
        }
        provChart.getData().add(provSeries);
        provChart.setPrefHeight(280);

        HBox chartsRow = new HBox(20, catChart, provChart);
        HBox.setHgrow(catChart, Priority.ALWAYS);
        HBox.setHgrow(provChart, Priority.ALWAYS);

        getChildren().addAll(summaryRow, chartsRow);
    }

    private VBox metric(String value, String label) {
        VBox box = new VBox(2);
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        Label l = new Label(label);
        l.getStyleClass().add("metric-label");
        box.getChildren().addAll(v, l);
        return box;
    }
}
