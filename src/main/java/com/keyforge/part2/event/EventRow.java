package com.keyforge.part2.event;

import java.time.Instant;

/**
 * One append-only {@code kf_event} row (Part 1 {@code iiq.event.EventRow}; the timestamp is carried as the UTC
 * {@link Instant} Part 1 stores from its UTC {@code LocalDateTime}).
 *
 * @param eventId             deterministic UUID, see {@link EventFingerprint#eventId}
 * @param srcSystem           {@code IdentityIQ}
 * @param srcObjectType       the IIQ class the event concerns (e.g. {@code sailpoint.object.TaskResult})
 * @param srcObjectId         the source record's id (its primary key / source_id in the native table)
 * @param eventType           normalized {@link EventType}
 * @param eventFingerprint    md5 over the event's business attributes
 * @param srcEventTs          when the event occurred, or {@code null} when the source has no such time
 * @param srcEventTsPrecision {@code precise} when {@code srcEventTs} is present, else {@code null}
 * @param extractionRunId     the derivation run that first recorded the event (kf_extraction_run)
 * @param srcInterface        {@code native_iiq_java_api} — the interface that produced the underlying source rows
 * @param eventDetailJson     small JSON of the event's real business attributes
 */
public record EventRow(
        String eventId,
        String srcSystem,
        String srcObjectType,
        String srcObjectId,
        String eventType,
        String eventFingerprint,
        Instant srcEventTs,
        String srcEventTsPrecision,
        String extractionRunId,
        String srcInterface,
        String eventDetailJson) {

    /** Precision marker for a source timestamp that is a real instant. */
    public static final String PRECISE = "precise";
}
