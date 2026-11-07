package com.mywatchmate.moderation;

import com.mywatchmate.moderation.dto.LinkDetail;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only evidence for the review screen: reports, rooms and gifts. Reporters are never identified. */
@Component
@RequiredArgsConstructor
class LinkEvidence {

    static final int MAX_REPORTS = 200;
    static final int MAX_ROOMS = 50;

    private final JdbcClient jdbc;

    List<LinkDetail.Report> reports(UUID linkId) {
        return jdbc.sql("""
                SELECT r.reason, r.reported_at, r.reporter_key LIKE 'guest:%' AS guest, s.title,
                       r.review_id IS NOT NULL AS reviewed
                FROM payment_link_reports r
                LEFT JOIN cue_detections d ON d.id = r.detection_id
                LEFT JOIN watch_sessions ws ON ws.id = d.session_id
                LEFT JOIN spaces s ON s.id = ws.space_id
                WHERE r.payment_link_id = :id
                ORDER BY r.reported_at DESC
                LIMIT :limit
                """)
            .param("id", linkId).param("limit", MAX_REPORTS)
            .query((rs, i) -> new LinkDetail.Report(rs.getString("reason"),
                instant(rs.getObject("reported_at", OffsetDateTime.class)),
                rs.getBoolean("guest") ? "GUEST" : "USER", rs.getString("title"), rs.getBoolean("reviewed")))
            .list();
    }

    List<LinkDetail.Room> rooms(UUID linkId) {
        return jdbc.sql("""
                SELECT s.id, s.title, count(*) AS shown, max(d.detected_at) AS last_shown,
                       bool_or(d.source = 'AI') AS by_ai, bool_or(d.source = 'HOST') AS by_host
                FROM cue_detections d
                JOIN watch_sessions ws ON ws.id = d.session_id
                JOIN spaces s ON s.id = ws.space_id
                WHERE d.payment_link_id = :id AND d.status = 'SHOWN'
                GROUP BY s.id, s.title
                ORDER BY last_shown DESC
                LIMIT :limit
                """)
            .param("id", linkId).param("limit", MAX_ROOMS)
            .query((rs, i) -> new LinkDetail.Room(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getLong("shown"), instant(rs.getObject("last_shown", OffsetDateTime.class)),
                rs.getBoolean("by_ai"), rs.getBoolean("by_host")))
            .list();
    }

    LinkDetail.Gifts gifts(UUID linkId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPENED') AS opened,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown
                FROM gifts WHERE payment_link_id = :id
                """)
            .param("id", linkId)
            .query((rs, i) -> new LinkDetail.Gifts(rs.getLong("opened"), rs.getLong("completed"), rs.getLong("failed"),
                rs.getLong("cancelled"), rs.getLong("unknown")))
            .single();
    }

    // Both timestamps read here are NOT NULL (reported_at; max() over at least one row)
    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
