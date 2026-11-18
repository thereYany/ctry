package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.ctry.modules.commerce.entitlement.EntitlementRepository;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.CatalogSeries;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 课程商店读模型：不再直接查课程/用户表，改为三段拼装。
 *
 * <pre>
 * 商品信息   ← course.api.CatalogQuery（内容域自己的表 + 身份域昵称接口）
 * 是否已购   ← entitlement（本模块内，t_student_series）
 * 进行中订单 ← order（本模块内，t_order）
 * </pre>
 *
 * <p>代价是 3 次查询代替 1 次 join；收益是商品展示不再依赖课程表结构，
 * 且跨域访问从 SQL 级降为接口级，后续分库/拆服务时这里只需换实现。</p>
 */
@Repository
public class CatalogRepository {

    private final CatalogQuery catalogQuery;
    private final EntitlementRepository entitlementRepository;
    private final OrderRepository orderRepository;

    public CatalogRepository(CatalogQuery catalogQuery,
                             EntitlementRepository entitlementRepository,
                             OrderRepository orderRepository) {
        this.catalogQuery = catalogQuery;
        this.entitlementRepository = entitlementRepository;
        this.orderRepository = orderRepository;
    }

    public List<CatalogItem> findOnSale(Long userId) {
        List<CatalogSeries> series = catalogQuery.findOnSale();
        if (series.isEmpty()) {
            return List.of();
        }
        Set<Long> owned = entitlementRepository.activeSeriesIds(userId);
        Map<Long, OrderRecord> activeOrders = new LinkedHashMap<>();
        for (OrderRecord order : orderRepository.findActiveOrders(userId)) {
            activeOrders.putIfAbsent(order.seriesId(), order);
        }
        return series.stream()
                .map(item -> {
                    OrderRecord active = activeOrders.get(item.seriesId());
                    return new CatalogItem(
                            item.seriesId(),
                            item.title(),
                            item.summary(),
                            item.teacherName(),
                            item.chapterCount(),
                            item.videoCount(),
                            item.priceCents(),
                            item.currency(),
                            owned.contains(item.seriesId()),
                            active == null ? null : active.orderNo(),
                            active == null ? null : active.status().name()
                    );
                })
                .toList();
    }

    public Optional<SaleSeries> findPurchasable(Long seriesId) {
        return catalogQuery.findPurchasable(seriesId)
                .map(item -> new SaleSeries(item.seriesId(), item.title(), item.priceCents(), item.currency()));
    }
}
