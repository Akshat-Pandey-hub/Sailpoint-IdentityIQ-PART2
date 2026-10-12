package com.keyforge.part2.ingest;

import java.util.ArrayList;
import java.util.List;

/** Mutable tally of one entity import: rows fetched/inserted/updated/failed, pages, and sample failures. */
public final class ImportResult {

    private final String entity;
    private final String targetTable;
    private int pages;
    private int fetched;
    private int inserted;
    private int updated;
    private int skipped;
    private int failed;
    private final List<String> failures = new ArrayList<>();

    public ImportResult(String entity, String targetTable) {
        this.entity = entity;
        this.targetTable = targetTable;
    }

    public void countPage() { pages++; }
    public void countFetched(int n) { fetched += n; }
    public void countInserted() { inserted++; }
    public void countUpdated() { updated++; }
    public void countSkipped() { skipped++; }

    public void countFailure(String ref, String message) {
        failed++;
        if (failures.size() < 50) {
            failures.add(ref + ": " + message);
        }
    }

    public String entity() { return entity; }
    public String targetTable() { return targetTable; }
    public int pages() { return pages; }
    public int fetched() { return fetched; }
    public int inserted() { return inserted; }
    public int updated() { return updated; }
    public int skipped() { return skipped; }
    public int failed() { return failed; }
    public int persisted() { return inserted + updated; }
    public List<String> failures() { return failures; }

    /** Multi-line human summary for the console. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(entity).append(" -> ").append(targetTable).append('\n');
        sb.append("  pages:     ").append(pages).append('\n');
        sb.append("  fetched:   ").append(fetched).append('\n');
        sb.append("  inserted:  ").append(inserted).append('\n');
        sb.append("  updated:   ").append(updated).append('\n');
        if (skipped > 0) {
            sb.append("  skipped:   ").append(skipped).append(" (already present; append-only, left unchanged)\n");
        }
        sb.append("  failed:    ").append(failed);
        int shown = Math.min(failures.size(), 10);
        for (int i = 0; i < shown; i++) {
            sb.append("\n    - ").append(failures.get(i));
        }
        return sb.toString();
    }
}
