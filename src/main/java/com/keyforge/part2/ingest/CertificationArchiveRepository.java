package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/certification-archives} → {@code kf_certification_archive}: IIQ {@code CertificationArchive}s, the
 * immutable snapshot IIQ keeps when a certification is archived. Reproduces Part 1's
 * {@code NativeCertificationArchiveRepository} + {@code NativeCertificationArchiveMapper}, which differ from every
 * current-state table:
 * <ul>
 *   <li><b>Append-only (CEC).</b> {@code INSERT … ON CONFLICT (certificationarchiveid) DO NOTHING}: never updated, never
 *       deleted. A re-run reports existing archives as {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code certificationarchiveid} = canonical source_id; a present but non-GUID id →
 *       {@code deterministicUuid("native-certification-archive|"+id)}; a null/blank id is refused (row fails).</li>
 *   <li>CEC lineage envelope instead of the source_system/is_deleted columns: {@code src_system}, {@code src_object_type},
 *       {@code src_object_id} (= source_id), {@code src_natural_key} (= archive name), {@code src_created} /
 *       {@code src_modified} (= created_at / modified_at), {@code src_event_ts} (= created_at, the archival time),
 *       {@code src_interface}, {@code extraction_run_id}, {@code raw_ref} and {@code derived_at} (both NULL, as in
 *       Part 1), {@code extracted_at}. {@code archived_ts} = created_at. The table has no is_deleted / deleted_at.</li>
 *   <li>Relationships (raw IIQ ids): {@code certification_id} = the certification that was archived
 *       ({@code kf_certification.source_id} while it existed); {@code child_certification_ids} (jsonb) = its child
 *       certifications; {@code certification_group_id} = the campaign. The archived entities and items are inside
 *       {@code archive_xml} (the verbatim archive document), not separate rows.</li>
 *   <li>{@code record_hash} = Part 1's 10-field hash: source_id, certification_id, certification_group_id, creator_name,
 *       owner_name, comments, signed, child_certification_ids, archive_xml, created_at.</li>
 * </ul>
 */
public final class CertificationArchiveRepository implements EntityWriter {

    public static final String RESOURCE = "certification-archives";
    public static final String TABLE = "kf_certification_archive";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.CertificationArchive";

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public CertificationArchiveRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        this.createTableSql = "CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "    certificationarchiveid uuid PRIMARY KEY,\n"
                + "    source_id text,\n"
                + "    name text,\n"
                + "    certification_id text,\n"
                + "    certification_group_id text,\n"
                + "    creator_name text,\n"
                + "    owner_name text,\n"
                + "    comments text,\n"
                + "    signed timestamp with time zone,\n"
                + "    expiration timestamp with time zone,\n"
                + "    child_certification_ids jsonb,\n"
                + "    archive_xml text,\n"
                + "    created_at timestamp with time zone,\n"
                + "    modified_at timestamp with time zone,\n"
                + "    archived_ts timestamp with time zone,\n"
                + "    record_hash text,\n"
                + "    src_system text,\n"
                + "    src_object_type text,\n"
                + "    src_object_id text,\n"
                + "    src_natural_key text,\n"
                + "    src_created timestamp with time zone,\n"
                + "    src_modified timestamp with time zone,\n"
                + "    src_event_ts timestamp with time zone,\n"
                + "    src_interface text,\n"
                + "    extraction_run_id text,\n"
                + "    raw_ref text,\n"
                + "    derived_at timestamp with time zone,\n"
                + "    extracted_at timestamp with time zone NOT NULL DEFAULT now()\n"
                + ")";
        this.appendSql = "INSERT INTO " + table + " (certificationarchiveid, source_id, name, certification_id, "
                + "certification_group_id, creator_name, owner_name, comments, signed, expiration, child_certification_ids, "
                + "archive_xml, created_at, modified_at, archived_ts, record_hash, src_system, src_object_type, "
                + "src_object_id, src_natural_key, src_created, src_modified, src_event_ts, src_interface, "
                + "extraction_run_id, raw_ref, derived_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::jsonb, ?, ?::timestamptz, "
                + "?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?, ?, ?, "
                + "?::timestamptz) "
                + "ON CONFLICT (certificationarchiveid) DO NOTHING RETURNING certificationarchiveid";
    }

    @Override
    public String entity() {
        return "CertificationArchive";
    }

    @Override
    public String resource() {
        return RESOURCE;
    }

    @Override
    public String targetTable() {
        return table;
    }

    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    /** Appends one archive; an archive that already exists is left untouched ({@code SKIPPED}). */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String sourceId = text(row, "source_id");
        String created = text(row, "created_at");
        String modified = text(row, "modified_at");
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            ps.setString(i++, sourceId);
            ps.setString(i++, text(row, "name"));
            ps.setString(i++, text(row, "certification_id"));
            ps.setString(i++, text(row, "certification_group_id"));
            ps.setString(i++, text(row, "creator_name"));
            ps.setString(i++, text(row, "owner_name"));
            ps.setString(i++, text(row, "comments"));
            ps.setString(i++, text(row, "signed"));
            ps.setString(i++, text(row, "expiration"));
            ps.setString(i++, json(row, "child_certification_ids"));
            ps.setString(i++, text(row, "archive_xml"));
            ps.setString(i++, created);
            ps.setString(i++, modified);
            ps.setString(i++, created);                     // archived_ts = created_at
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i++, sourceId);                    // src_object_id
            ps.setString(i++, text(row, "name"));           // src_natural_key = archive name
            ps.setString(i++, created);                     // src_created
            ps.setString(i++, modified);                    // src_modified
            ps.setString(i++, created);                     // src_event_ts = archival time
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, extractionRunId);
            ps.setString(i++, null);                        // raw_ref: not wired, as in Part 1
            ps.setNull(i, Types.VARCHAR);                   // derived_at: extracted, not derived
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.SKIPPED;
            }
        }
    }

    /** Part 1 {@code NativeCertificationArchiveRepository.canonicalArchiveId}. */
    public static String id(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException(
                        "CertificationArchive source id is required; certificationarchiveid must derive from getId()");
            }
            id = CanonicalIds.deterministicUuid("native-certification-archive|" + sourceId);
        }
        return id;
    }

    /** Part 1 {@code NativeCertificationArchiveRepository.recordHash}: 10 fields. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("certification_id", text(r, "certification_id"));
        b.put("certification_group_id", text(r, "certification_group_id"));
        b.put("creator_name", text(r, "creator_name"));
        b.put("owner_name", text(r, "owner_name"));
        b.put("comments", text(r, "comments"));
        b.put("signed", instant(r, "signed"));
        b.put("child_certification_ids", json(r, "child_certification_ids"));
        b.put("archive_xml", text(r, "archive_xml"));
        b.put("created_at", instant(r, "created_at"));
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "certification archive " + text(row, "name") + " (source_id=" + text(row, "source_id") + ")";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String appendSql() {
        return appendSql;
    }
}
