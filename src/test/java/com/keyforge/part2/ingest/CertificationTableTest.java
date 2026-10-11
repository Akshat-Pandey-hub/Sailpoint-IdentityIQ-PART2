package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keyforge.part2.id.CanonicalIds;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_certification. Golden fixture produced by Part 1's own NativeCertificationParser / Repository / REST toJson, with
 * allowed_statuses / tags sent as IIQ JSON-array strings (re-hydrated by the KF Agent) and a non-JSON tag string.
 * Covers key + fallbacks, the 26-field hash, JSON_TEXT round-trip, trailing-column DDL, binding.
 */
class CertificationTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "certificationid", "source_id", "name", "certification_name", "short_name", "type", "phase", "comments",
            "creator", "manager", "certification_group_id", "certification_group_name", "certification_definition_id",
            "group_definition_id", "group_definition_name", "application_id", "task_schedule_id", "trigger_id",
            "parent_id", "complete", "expired", "continuous", "electronically_signed", "signed", "finished", "activated",
            "expiration", "created_at", "modified_at", "total_items", "completed_items", "open_items", "total_entities",
            "completed_entities", "open_entities", "percent_complete", "certifiers", "sign_off_history", "owner_id",
            "owner_name", "record_hash", "source_system", "source_interface", "source_object_type", "extraction_run_id",
            "extracted_at", "is_deleted", "deleted_at", "approver_rule", "automatic_closing_date", "allowed_statuses",
            "tags");

    /** Response fields Part 1 leaves OUT of record_hash. */
    private static final Set<String> NOT_HASHED = Set.of("certification_name", "short_name", "certification_group_name",
            "group_definition_id", "group_definition_name", "application_id", "task_schedule_id", "trigger_id",
            "parent_id", "continuous", "electronically_signed", "created_at", "modified_at", "total_entities",
            "completed_entities", "open_entities", "owner_name");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", CertificationTable.SPEC);

    private record Golden(JsonNode rest, String statuses, String tags, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/certification-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, st = null, tg = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("STATUSES=")) st = "null".equals(line.substring(9)) ? null : line.substring(9);
                else if (line.startsWith("TAGS=")) tg = "null".equals(line.substring(5)) ? null : line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), st, tg, hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), CertificationTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), CertificationTable.certificationId(g.rest()), "certificationid parity");
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3471dd", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-certification|No Id Cert"), gs.get(1).id());
        assertEquals(CanonicalIds.deterministicUuid("native-certification|not-a-guid"), gs.get(2).id());
    }

    @Test
    void allowedStatusesAndTagsAreStoredAsThePart1Text() throws Exception {
        List<Golden> gs = golden();
        for (int i = 0; i < gs.size(); i++) {
            RecordingJdbc jdbc = new RecordingJdbc();
            repo.upsert(jdbc.connection(), gs.get(i).rest(), "run-1");
            Map<Integer, Object> b = jdbc.executions.get(0);
            Object statuses = b.get(col("allowed_statuses"));
            Object tags = b.get(col("tags"));
            assertEquals(gs.get(i).statuses(), statuses == NULL ? null : statuses, "allowed_statuses text, row " + i);
            assertEquals(gs.get(i).tags(), tags == NULL ? null : tags, "tags text, row " + i);
        }
        assertTrue(gs.get(0).rest().get("allowed_statuses").isArray(), "the KF Agent re-hydrates the array");
        assertEquals("not-json tag", gs.get(1).tags(), "a non-JSON string passes through verbatim");
    }

    @Test
    void hashCoversTwentySixFields() throws Exception {
        Golden g = golden().get(0);
        int hashed = 0;
        for (ColumnSpec c : CertificationTable.SPEC.allBusiness()) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 99);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                case JSON_TEXT -> changed.putArray(c.name).add("Changed");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if (NOT_HASHED.contains(c.name)) {
                assertEquals(g.hash(), CertificationTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), CertificationTable.recordHash(changed), c.name + " must be hashed");
                hashed++;
            }
        }
        assertEquals(26, hashed);
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        CertificationTable.SPEC.allBusiness().forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(43, mapped.size());
    }

    @Test
    void ddlMatchesIiqNativeIncludingTrailingTextColumns() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("allowed_statuses text") && ddl.contains("tags text"), "text in iiq_native, not jsonb");
        assertTrue(ddl.contains("automatic_closing_date text") && ddl.contains("certifiers jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (certificationid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(49, vals.chars().filter(ch -> ch == '?').count(), "pk + 43 + record_hash + 4 lineage");
    }

    @Test
    void bindsHeaderFieldsCountsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-61"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3471dd", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3471d0", b.get(col("certification_group_id")), "campaign link kept raw");
        assertEquals(12, b.get(col("total_items")));
        assertEquals(0, b.get(col("percent_complete")), "0 is a value");
        assertEquals("[\"Alexander Evans\"]", b.get(col("certifiers")));
        assertEquals("[]", b.get(col("sign_off_history")));
        assertSame(NULL, b.get(col("signed")));

        int hashIdx = 2 + CertificationTable.SPEC.allBusiness().size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Certification", b.get(hashIdx + 3));
        assertEquals("run-61", b.get(hashIdx + 4));
    }

    private static int col(String name) {
        List<ColumnSpec> all = CertificationTable.SPEC.allBusiness();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
