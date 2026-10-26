package com.keyforge.part2.http;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Validated caller-supplied source scope for one REST-triggered import. */
public record FetchOptions(Integer start, Integer limit, String modifiedAfter, Map<String, String> filters) {
    public FetchOptions {
        filters = filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }

    public static FetchOptions none() {
        return new FetchOptions(null, null, null, Map.of());
    }

    public boolean isEmpty() {
        return start == null && limit == null && modifiedAfter == null && filters.isEmpty();
    }

    public boolean hasWindow() {
        return start != null;
    }
}
