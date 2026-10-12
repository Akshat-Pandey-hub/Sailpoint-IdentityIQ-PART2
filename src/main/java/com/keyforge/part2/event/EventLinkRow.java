package com.keyforge.part2.event;

/**
 * One append-only native {@code kf_event_link} row (Part 1 {@code iiq.nativeevent.NativeEventLinkRow}). Every link is
 * taken from an explicit source id column, never from a name, timestamp, ordering or similarity, so
 * {@code linkStatus} is always {@code EXPLICIT} and {@code targetObjectId} is the trimmed id the source row carried.
 *
 * @param linkId           deterministic UUID of {@code eventId|linkType|targetObjectType|targetObjectId}
 * @param eventId          the {@code kf_event.event_id} this link belongs to
 * @param srcObjectType    the event's source IIQ class (provenance)
 * @param srcObjectId      the native source record id that produced the event and the link
 * @param targetObjectType the referenced object kind (Identity / IdentityRequest / WorkItem / ...)
 * @param targetObjectId   the explicit source id reference, trimmed (never resolved by name)
 * @param linkType         the explicit relationship (e.g. APPROVAL_OF_REQUEST)
 * @param linkStatus       always {@code EXPLICIT}
 * @param extractionRunId  the run that derived this row (kf_extraction_run)
 */
public record EventLinkRow(
        String linkId,
        String eventId,
        String srcObjectType,
        String srcObjectId,
        String targetObjectType,
        String targetObjectId,
        String linkType,
        String linkStatus,
        String extractionRunId) {

    public static final String EXPLICIT = "EXPLICIT";
}
