package veil.ui.views;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import veil.core.VeilCoreEngine;
import veil.models.RequestEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Screen E: Activity Monitor — genuine request metadata log (never stores raw sensitive prompt text).
 */
public class ActivityView extends VBox {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public ActivityView(VeilCoreEngine core) {
        getStyleClass().add("content-stage");
        setSpacing(20);

        Label kicker = new Label("05 / LIVE TELEMETRY");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Activity Monitor");
        title.getStyleClass().add("display-heading");

        Label desc = new Label("Displays genuine request metadata processed by VEIL. Raw sensitive prompt content is never stored.");
        desc.getStyleClass().add("body-muted");

        Region div = new Region();
        div.getStyleClass().add("hairline-divider");

        List<RequestEvent> events = core.getAnalyticsService().getRecentEvents();

        TableView<RequestEvent> table = new TableView<>(FXCollections.observableArrayList(events));
        table.setPrefHeight(480);
        table.setPlaceholder(new Label("No gateway or playground requests recorded yet."));

        TableColumn<RequestEvent, String> tsCol = new TableColumn<>("TIMESTAMP");
        tsCol.setCellValueFactory(d -> new SimpleStringProperty(FMT.format(d.getValue().getTimestamp())));
        tsCol.setPrefWidth(155);

        TableColumn<RequestEvent, String> srcCol = new TableColumn<>("SOURCE");
        srcCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getSource()));
        srcCol.setPrefWidth(95);

        TableColumn<RequestEvent, String> provCol = new TableColumn<>("PROVIDER");
        provCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getProvider() == null ? "—" : d.getValue().getProvider().getDisplayName()));
        provCol.setPrefWidth(120);

        TableColumn<RequestEvent, String> modelCol = new TableColumn<>("MODEL");
        modelCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getModel() == null ? "—" : d.getValue().getModel()));
        modelCol.setPrefWidth(150);

        TableColumn<RequestEvent, String> statusCol = new TableColumn<>("STATUS");
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getStatus().name()));
        statusCol.setPrefWidth(125);

        TableColumn<RequestEvent, String> detCol = new TableColumn<>("DETECTED");
        detCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getDetectedCount())));
        detCol.setPrefWidth(80);

        TableColumn<RequestEvent, String> maskCol = new TableColumn<>("MASKED");
        maskCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getMaskedCount())));
        maskCol.setPrefWidth(75);

        TableColumn<RequestEvent, String> durCol = new TableColumn<>("DURATION");
        durCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getDurationMs() + " ms"));
        durCol.setPrefWidth(85);

        table.getColumns().addAll(tsCol, srcCol, provCol, modelCol, statusCol, detCol, maskCol, durCol);

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> table.setItems(FXCollections.observableArrayList(core.getAnalyticsService().getRecentEvents())));

        Button clearBtn = new Button("Clear Activity History");
        clearBtn.getStyleClass().add("btn-danger");
        clearBtn.setOnAction(e -> {
            core.getAnalyticsService().clearAllHistory();
            table.setItems(FXCollections.observableArrayList());
        });

        getChildren().addAll(kicker, title, desc, div, new HBox(10, refreshBtn, clearBtn), table);
    }
}
