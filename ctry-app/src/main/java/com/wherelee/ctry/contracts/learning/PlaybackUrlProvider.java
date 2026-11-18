package com.wherelee.ctry.contracts.learning;

import java.time.Instant;

/**
 * 播放地址提供端口。
 */
public interface PlaybackUrlProvider {

    PlaybackUrl get(String videoKey);

    record PlaybackUrl(String url, Instant expiresAt, boolean cacheHit) {
    }
}