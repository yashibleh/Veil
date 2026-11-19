package veil.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import veil.models.EntityCategory;
import veil.models.ProviderType;
import veil.models.RequestEvent;
import veil.utils.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Genuine SQLite-backed activity and analytics store.
 * Stores metadata only (never raw sensitive prompt text).
 * Produces honest zero/empty snapshots when no requests have been processed.
 */
public class AnalyticsService {

    private final Path dbPath;
    private final List<RequestEvent> recentEvents = new CopyOnWriteArrayList<>();

    public AnalyticsService(Path dbPath) {
        this.dbPath = dbPath;
        initializeDatabase();
        loadRecentFromDatabase(500);
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private void initializeDatabase() {
        if (dbPath == null) {
            return;
        }
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS request_events (
                            id TEXT PRIMARY KEY,
                            timestamp TEXT NOT NULL,
                            session_id TEXT,
                            source TEXT,
                            provider TEXT,
                            model TEXT,
                            profile_name TEXT,
                            status TEXT NOT NULL,
                            detected_count INTEGER NOT NULL,
                            masked_count INTEGER NOT NULL,
                            blocked INTEGER NOT NULL,
                            streaming INTEGER NOT NULL,
                            duration_ms INTEGER NOT NULL,
                            summary_note TEXT,
                            category_counts_json TEXT
                        )
                        """);
            }
        } catch (Exception ignored) {
            // Fall back to in-memory event list if SQLite cannot initialize
        }
    }

    public synchronized void recordEvent(RequestEvent event) {
        if (event == null) {
            return;
        }
        recentEvents.add(0, event);
        if (recentEvents.size() > 1000) {
            recentEvents.remove(recentEvents.size() - 1);
        }
        if (dbPath == null) {
            return;
        }
        String sql = """
                INSERT OR REPLACE INTO request_events
                (id, timestamp, session_id, source, provider, model, profile_name, status,
                 detected_count, masked_count, blocked, streaming, duration_ms, summary_note, category_counts_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.getId());
            ps.setString(2, event.getTimestamp().toString());
            ps.setString(3, event.getSessionId());
            ps.setString(4, event.getSource());
            ps.setString(5, event.getProvider() == null ? null : event.getProvider().name());
            ps.setString(6, event.getModel());
            ps.setString(7, event.getProfileName());
            ps.setString(8, event.getStatus().name());
            ps.setInt(9, event.getDetectedCount());
            ps.setInt(10, event.getMaskedCount());
            ps.setInt(11, event.isBlocked() ? 1 : 0);
            ps.setInt(12, event.isStreaming() ? 1 : 0);
            ps.setLong(13, event.getDurationMs());
            ps.setString(14, event.getSummaryNote());
            ps.setString(15, JsonUtil.mapper().writeValueAsString(event.getCategoryCounts()));
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void loadRecentFromDatabase(int limit) {
        if (dbPath == null || !Files.exists(dbPath)) {
            return;
        }
        String sql = "SELECT * FROM request_events ORDER BY timestamp DESC LIMIT ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<RequestEvent> loaded = new ArrayList<>();
                while (rs.next()) {
                    RequestEvent e = new RequestEvent();
                    e.setId(rs.getString("id"));
                    e.setTimestamp(Instant.parse(rs.getString("timestamp")));
                    e.setSessionId(rs.getString("session_id"));
                    e.setSource(rs.getString("source"));
                    String prov = rs.getString("provider");
                    if (prov != null) {
                        try {
                            e.setProvider(ProviderType.valueOf(prov));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    e.setModel(rs.getString("model"));
                    e.setProfileName(rs.getString("profile_name"));
                    e.setStatus(RequestEvent.Status.valueOf(rs.getString("status")));
                    e.setDetectedCount(rs.getInt("detected_count"));
                    e.setMaskedCount(rs.getInt("masked_count"));
                    e.setBlocked(rs.getInt("blocked") == 1);
                    e.setStreaming(rs.getInt("streaming") == 1);
                    e.setDurationMs(rs.getLong("duration_ms"));
                    e.setSummaryNote(rs.getString("summary_note"));
                    String catJson = rs.getString("category_counts_json");
                    if (catJson != null && !catJson.isBlank()) {
                        Map<EntityCategory, Integer> cats = JsonUtil.mapper()
                                .readValue(catJson, new TypeReference<Map<EntityCategory, Integer>>() {});
                        e.setCategoryCounts(cats);
                    }
                    loaded.add(e);
                }
                recentEvents.clear();
                recentEvents.addAll(loaded);
            }
        } catch (Exception ignored) {
        }
    }

    public List<RequestEvent> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public AnalyticsSnapshot computeSnapshot() {
        long total = recentEvents.size();
        long forwarded = 0;
        long blocked = 0;
        long errors = 0;
        long detected = 0;
        long masked = 0;
        long totalDuration = 0;
        Map<EntityCategory, Long> catDist = new EnumMap<>(EntityCategory.class);
        Map<ProviderType, Long> provUsage = new EnumMap<>(ProviderType.class);

        for (RequestEvent e : recentEvents) {
            if (e.getStatus() == RequestEvent.Status.FORWARDED) {
                forwarded++;
            } else if (e.isBlocked() || e.getStatus() == RequestEvent.Status.BLOCKED || e.getStatus() == RequestEvent.Status.WARN_DENIED) {
                blocked++;
            } else {
                errors++;
            }
            detected += e.getDetectedCount();
            masked += e.getMaskedCount();
            totalDuration += e.getDurationMs();

            if (e.getProvider() != null) {
                provUsage.merge(e.getProvider(), 1L, Long::sum);
            }
            if (e.getCategoryCounts() != null) {
                for (Map.Entry<EntityCategory, Integer> ce : e.getCategoryCounts().entrySet()) {
                    catDist.merge(ce.getKey(), ce.getValue().longValue(), Long::sum);
                }
            }
        }

        double avgDuration = total == 0 ? 0.0 : (double) totalDuration / total;
        return new AnalyticsSnapshot(total, forwarded, blocked, errors, detected, masked, avgDuration, catDist, provUsage);
    }

    public synchronized void pruneOlderThanDays(int retentionDays) {
        Instant cutoff = Instant.now().minus(Math.max(1, retentionDays), ChronoUnit.DAYS);
        recentEvents.removeIf(e -> e.getTimestamp().isBefore(cutoff));
        if (dbPath == null) {
            return;
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM request_events WHERE timestamp < ?")) {
            ps.setString(1, cutoff.toString());
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public synchronized void clearAllHistory() {
        recentEvents.clear();
        if (dbPath == null) {
            return;
        }
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM request_events");
        } catch (Exception ignored) {
        }
    }
}
