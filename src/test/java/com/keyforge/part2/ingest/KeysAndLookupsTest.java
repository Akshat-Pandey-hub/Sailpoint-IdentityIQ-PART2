package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.id.CanonicalIds;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Row keys without an IIQ id, and the cross-table lookups that must ignore rows marked deleted. */
class KeysAndLookupsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aRowWithoutAnIiqIdGetsAStableUuidFromItsWholeContent() throws Exception {
        JsonNode a = mapper.readTree("{\"source_id\":null,\"action\":\"login\",\"target\":\"alice\",\"n\":1}");
        JsonNode sameReordered = mapper.readTree("{\"n\":1,\"target\":\"alice\",\"action\":\"login\",\"source_id\":null}");
        JsonNode other = mapper.readTree("{\"source_id\":null,\"action\":\"login\",\"target\":\"bob\",\"n\":1}");
        assertEquals(CanonicalIds.contentUuid("p", a), CanonicalIds.contentUuid("p", sameReordered),
                "the same record always gets the same uuid (field order does not matter)");
        assertNotEquals(CanonicalIds.contentUuid("p", a), CanonicalIds.contentUuid("p", other),
                "two different records never share a uuid, even with the same action");
        assertEquals(AuditEventRepository.id(a), AuditEventRepository.id(sameReordered));
        assertNotEquals(AuditEventRepository.id(a), AuditEventRepository.id(other));
    }

    @Test
    void aRowWithAnIiqIdKeepsItsIdBasedUuid() throws Exception {
        JsonNode withId = mapper.readTree("{\"source_id\":\"7f0001019f061fdc819f34b0ac3471dd\",\"name\":\"x\"}");
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3471dd", CertificationTable.certificationId(withId));
        JsonNode byName = mapper.readTree("{\"source_id\":null,\"name\":\"Alice\"}");
        assertEquals(CanonicalIds.identityUserid(null, "Alice"), IdentityRepository.userid(byName),
                "an identity without an id keeps its unique name as its key");
        JsonNode nothing = mapper.readTree("{\"source_id\":null,\"name\":null,\"email\":\"a@b\"}");
        assertEquals(CanonicalIds.contentUuid("native-identity", nothing), IdentityRepository.userid(nothing),
                "neither id nor name: the content");
    }

    @Test
    void roleEntitlementsLinkOnlyToLiveEntitlements() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        new RoleEntitlementRepository("s").resolveEntitlement(jdbc.connection(), RoleEntitlementRepository.Edge.of(
                mapper.readTree("{\"source_role_id\":\"r\",\"entitlement_type\":\"PROFILE_CONSTRAINT\","
                        + "\"filter_operation\":\"EQ\",\"application_name\":\"EBM\",\"attribute_name\":\"roles\","
                        + "\"attribute_value\":\"EBM_FIN_AP_APPROVE\"}")));
        String lookup = jdbc.preparedSql.get(0);
        assertTrue(lookup.contains("FROM s.kf_entitlement") && lookup.contains("AND NOT is_deleted"),
                "a deleted copy with the same name never blocks or takes the link: " + lookup);
    }
}
