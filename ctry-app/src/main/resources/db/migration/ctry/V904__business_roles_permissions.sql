-- 网课垂直域的角色与权限种子：内核只种了 ADMIN 与通用权限，业务角色/权限在这里定义。
-- 角色码是数据，新增角色不需要改内核代码。

INSERT INTO t_role (tenant_id, code, name, description, built_in)
VALUES (0, 'TEACHER', '教师', '内容创作与发布', 1),
       (0, 'STUDENT', '学员', '购买课程与学习', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO t_permission (tenant_id, code, name)
VALUES (0, 'course:catalog:read', '查看课程目录'),
       (0, 'course:series:read', '查看课程详情'),
       (0, 'order:create', '创建订单'),
       (0, 'order:read', '查看订单'),
       (0, 'order:pay', '发起支付'),
       (0, 'order:cancel', '取消订单'),
       (0, 'learning:progress:write', '上报学习进度'),
       (0, 'teacher:series:manage', '管理本人系列'),
       (0, 'teacher:content:manage', '管理系列内容'),
       (0, 'teacher:video:upload', '上传视频'),
       (0, 'admin:order:manage', '管理订单'),
       (0, 'admin:series:manage', '治理课程')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 管理员拥有全部权限（含业务权限）
INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0
WHERE r.tenant_id = 0
  AND r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1
                  FROM t_role_permission rp
                  WHERE rp.tenant_id = 0 AND rp.role_id = r.id AND rp.permission_id = p.id);

-- 教师：看课程 + 管理自己的系列/内容/上传
INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0
WHERE r.tenant_id = 0
  AND r.code = 'TEACHER'
  AND p.code IN ('course:catalog:read', 'course:series:read',
                 'teacher:series:manage', 'teacher:content:manage', 'teacher:video:upload');

-- 学员：看课程 + 下单支付 + 学习
INSERT INTO t_role_permission (tenant_id, role_id, permission_id)
SELECT 0, r.id, p.id
FROM t_role r
         JOIN t_permission p ON p.tenant_id = 0
WHERE r.tenant_id = 0
  AND r.code = 'STUDENT'
  AND p.code IN ('course:catalog:read', 'course:series:read',
                 'order:create', 'order:read', 'order:pay', 'order:cancel',
                 'payment:mock', 'learning:progress:write');
