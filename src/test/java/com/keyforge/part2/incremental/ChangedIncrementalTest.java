package com.keyforge.part2.incremental;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stage-2 cursor ({@link IncrementalCursor#CHANGED}): the watermark is the newest of {@code modified_at} and, for rows
 * IIQ never modified, {@code created_at}; the field recorded with it is {@code modified}. The existing cursors keep
 * their exact SQL.
 */
class ChangedIncrementalTest {

    /** Answers the max query with {@code value} and records the SQL. */
    private static Connection maxAnswering(List<String> sql, Instant value) {
        return (Connection) Proxy.newProxyInstance(ChangedIncrementalTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (!m.getName().equals("createStatement")) return null;
                    return Proxy.newProxyInstance(ChangedIncrementalTest.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, (p2, m2, a2) -> {
                                if (!m2.getName().equals("executeQuery")) return null;
                                sql.add((String) a2[0]);
                                boolean[] done = {false};
                                return Proxy.newProxyInstance(ChangedIncrementalTest.class.getClassLoader(),
                                        new Class<?>[]{ResultSet.class}, (p3, m3, a3) -> switch (m3.getName()) {
                                            case "next" -> {
                                                boolean has = !done[0];
                                                done[0] = true;
                                                yield has;
                                            }
                                            case "getObject" -> OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
                                            default -> null;
                                        });
                            });
                });
    }

    @Test
    void changedCursorTakesTheNewestOfModifiedOrCreated() throws Exception {
        List<String> sql = new ArrayList<>();
        Instant t = Instant.parse("2026-10-12T10:00:00Z");
        Instant got = new WatermarkService("s").maxValue(maxAnswering(sql, t), "s.kf_task_result",
                IncrementalCursor.CHANGED);
        assertEquals(t, got);
        assertEquals("SELECT max(coalesce(modified_at, created_at)) FROM s.kf_task_result", sql.get(0));
        assertEquals("modified", IncrementalCursor.CHANGED.sourceField());
    }

    @Test
    void existingCursorsKeepTheirExactSql() throws Exception {
        List<String> sql = new ArrayList<>();
        WatermarkService w = new WatermarkService("s");
        Instant t = Instant.parse("2026-10-12T10:00:00Z");
        w.maxModified(maxAnswering(sql, t), "s.kf_identity");
        w.maxValue(maxAnswering(sql, t), "s.kf_audit_event", IncrementalCursor.CREATED);
        assertEquals(List.of("SELECT max(modified_at) FROM s.kf_identity",
                "SELECT max(created_at) FROM s.kf_audit_event"), sql);
    }
}
