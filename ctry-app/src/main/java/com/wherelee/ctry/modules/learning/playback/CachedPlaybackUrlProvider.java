package com.wherelee.ctry.modules.learning.playback;

import com.wherelee.ctry.contracts.learning.PlaybackUrlProvider;
import com.wherelee.edu.modules.platform.storage.SignedUrlCache;
import com.wherelee.edu.modules.platform.tracing.TraceSpans;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 将现有 Redis/COS URL 缓存适配为 learning 模块公开的播放地址端口。
 */
@Service
public class CachedPlaybackUrlProvider implements PlaybackUrlProvider {

    private final SignedUrlCache cache;
    private final TraceSpans traceSpans;

    public CachedPlaybackUrlProvider(SignedUrlCache cache, TraceSpans traceSpans) {
        this.cache = cache;
        this.traceSpans = traceSpans;
    }

    @Override
    public PlaybackUrl get(String videoKey) {
        return traceSpans.trace("playback.url.resolve", TraceSpans.tags("video.key", videoKey), span -> {
            SignedUrlCache.CachedUrl cached = cache.get(videoKey);
            span.tag("cache.hit", String.valueOf(cached.cacheHit()));
            return new PlaybackUrl(cached.url(), cached.expiresAt(), cached.cacheHit());
        });
    }
}
