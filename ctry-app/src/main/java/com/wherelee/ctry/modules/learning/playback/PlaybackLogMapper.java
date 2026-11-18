package com.wherelee.ctry.modules.learning.playback;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlaybackLogMapper {

    int insertBatch(@Param("events") List<PlaybackLogEvent> events,
                    @Param("tenantId") Long tenantId);

    List<PlaybackLogVO> findRecentByUser(@Param("tenantId") Long tenantId,
                                         @Param("userId") Long userId,
                                         @Param("eventType") String eventType,
                                         @Param("limit") int limit);
}