package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Response values reach their columns exactly as Part 1 sent them. */
class StoredAsReceivedTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aStructuredValueInATextColumnIsKeptAsItsJson() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        var ps = jdbc.connection().prepareStatement("INSERT");
        JsonColumnBinder.bind(ps, 1, ColumnSpec.text("x"), mapper.readTree("{\"a\":[1,2]}"));
        JsonColumnBinder.bind(ps, 2, ColumnSpec.text("y"), mapper.readTree("[\"r\",\"w\"]"));
        JsonColumnBinder.bind(ps, 3, ColumnSpec.text("z"), mapper.readTree("\"plain\""));
        JsonColumnBinder.bind(ps, 4, ColumnSpec.text("n"), mapper.readTree("42"));
        ps.executeQuery();
        var b = jdbc.executions.get(0);
        assertEquals("{\"a\":[1,2]}", b.get(1), "never an empty string");
        assertEquals("[\"r\",\"w\"]", b.get(2));
        assertEquals("plain", b.get(3));
        assertEquals("42", b.get(4));
    }

    @Test
    void anEarlierWorkgroupMemberTableGainsWorkgroupName() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        new WorkgroupMemberRepository("s").ensureSchemaAndTable(jdbc.connection());
        assertTrue(jdbc.ddl.contains("ALTER TABLE s.kf_workgroup_member ADD COLUMN IF NOT EXISTS workgroup_name text"),
                String.valueOf(jdbc.ddl));
    }
}
