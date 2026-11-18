package com.wherelee.ctry.contracts.commerce;

import java.util.List;

/**
 * 权益管理写侧契约（运营端分配/回收课程）。
 *
 * <p>运营端不直接写权益表：它只能通过这个契约表达"这个人应该有哪些管理分配权益"，
 * 具体行怎么写、source 怎么标记由权益域决定。</p>
 */
public interface EntitlementAdmin {

    /** 该用户由管理员分配（source=ADMIN）且当前有效的课程。 */
    List<Long> adminSeriesIds(Long userId);

    /** 用给定集合替换该用户的管理员分配权益（幂等，全量覆盖）。 */
    void replaceAdminGrants(Long userId, List<Long> seriesIds);
}
