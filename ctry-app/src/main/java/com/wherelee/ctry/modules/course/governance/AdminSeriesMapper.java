package com.wherelee.ctry.modules.course.governance;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminSeriesMapper {

    List<AdminSeriesVO> findPage(@Param("keyword") String keyword,
                                 @Param("status") String status,
                                 @Param("teacherId") Long teacherId,
                                 @Param("teacherIds") List<Long> teacherIds,
                                 @Param("offset") int offset,
                                 @Param("size") int size,
                                 @Param("tenantId") Long tenantId);

    long count(@Param("keyword") String keyword,
               @Param("status") String status,
               @Param("teacherId") Long teacherId,
               @Param("teacherIds") List<Long> teacherIds,
               @Param("tenantId") Long tenantId);

    AdminSeriesHeaderVO findHeader(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<AdminSeriesChapterVO> findChapters(@Param("seriesId") Long seriesId,
                                           @Param("tenantId") Long tenantId);

    List<AdminSeriesVideoVO> findVideos(@Param("seriesId") Long seriesId,
                                       @Param("tenantId") Long tenantId);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("tenantId") Long tenantId);

    int updateSale(@Param("id") Long id,
                   @Param("saleStatus") String saleStatus,
                   @Param("priceCents") long priceCents,
                   @Param("tenantId") Long tenantId);
}
