package com.wherelee.ctry.modules;

/**
 * 网课垂直域的权限码目录。
 *
 * <p>内核只提供通用管理权限码；课程/订单/学习/教师相关权限码属于本垂直实现，
 * 与 t_permission 里的种子数据一一对应。</p>
 */
public final class CtryPermissionCodes {

    public static final String COURSE_CATALOG_READ = "course:catalog:read";
    public static final String COURSE_SERIES_READ = "course:series:read";
    public static final String ORDER_CREATE = "order:create";
    public static final String ORDER_READ = "order:read";
    public static final String ORDER_PAY = "order:pay";
    public static final String ORDER_CANCEL = "order:cancel";
    public static final String LEARNING_PROGRESS_WRITE = "learning:progress:write";
    public static final String TEACHER_SERIES_MANAGE = "teacher:series:manage";
    public static final String TEACHER_CONTENT_MANAGE = "teacher:content:manage";
    public static final String TEACHER_VIDEO_UPLOAD = "teacher:video:upload";
    public static final String ADMIN_ORDER_MANAGE = "admin:order:manage";
    public static final String ADMIN_SERIES_MANAGE = "admin:series:manage";

    private CtryPermissionCodes() {
    }
}
