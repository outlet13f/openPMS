package com.openpms.auth;

import com.openpms.common.ApiException;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory token buckets per client address for sign-in and mail-sending routes (one app instance). */
@Component
public class RateLimiter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Allow {@code perMinute} requests per minute for this key; beyond that, 429. */
    public void check(String key, int perMinute) {
        if (buckets.size() > 10_000) buckets.clear(); // crude bound; the window is short anyway
        var b = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(l -> l.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build());
        if (!b.tryConsume(1)) throw new ApiException(429, "요청이 너무 많습니다.", "잠시 뒤 다시 시도하세요.");
    }
}
