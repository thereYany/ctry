/**
 * 网课垂直实现（ctry）的模块边界。
 *
 * <p>course（课程内容）/ learning（学习与播放）/ commerce（订单与权益）/ operations（运营端）
 * 只允许通过 contracts 包与内核交互；模块依赖由 ArchUnit 门禁守护。</p>
 */
package com.wherelee.ctry.modules;
