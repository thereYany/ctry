package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.ctry.modules.commerce.order.CatalogItem;
import com.wherelee.ctry.modules.commerce.order.CatalogRepository;
import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.ctry.modules.commerce.order.OrderService;
import com.wherelee.ctry.modules.commerce.order.SaleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 期证据测试：课程商店读模型改为「三段拼装」后行为不变。
 *
 * <p>原实现是一条 SQL join 六张表（商品/章节/视频/教师/权益/订单）；
 * 现在拆成课程域目录接口 + 权益查询 + 订单查询。这个测试就是"行为等价"的回归证据。</p>
 */
class CatalogCompositionIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private OrderService orderService;
    @Autowired
    private CatalogRepository catalogRepository;

    @Test
    @DisplayName("商店列表：商品来自课程域、已购来自权益、进行中订单来自订单域")
    void catalogAssemblesFromThreeSources() {
        // 1) 买下 fixture 系列 → 权益生效
        String paidOrderNo = createOrder(nextRequestId()).orderNo();
        completePayment(paidOrderNo);

        // 2) 新增一门在售系列，并创建未支付订单
        Long secondSeriesId = insertOnSaleSeries(fx.teacherId(), "第二门课", 29900L);
        OrderDtos.OrderView pending = orderService.create(
                fx.studentId(), new OrderDtos.CreateOrderRequest(secondSeriesId, nextRequestId()));

        List<CatalogItem> catalog = orderService.catalog(fx.studentId());
        assertThat(catalog).hasSize(2);

        CatalogItem owned = catalog.stream()
                .filter(item -> item.seriesId().equals(fx.seriesId()))
                .findFirst().orElseThrow();
        assertThat(owned.owned()).isTrue();
        assertThat(owned.activeOrderNo()).isNull();
        assertThat(owned.teacherName()).isEqualTo("集成测试老师");
        assertThat(owned.chapterCount()).isEqualTo(1);
        assertThat(owned.videoCount()).isEqualTo(1);
        assertThat(owned.priceCents()).isEqualTo(fx.priceCents());

        CatalogItem pendingItem = catalog.stream()
                .filter(item -> item.seriesId().equals(secondSeriesId))
                .findFirst().orElseThrow();
        assertThat(pendingItem.owned()).isFalse();
        assertThat(pendingItem.activeOrderNo()).isEqualTo(pending.orderNo());
        assertThat(pendingItem.activeOrderStatus()).isEqualTo("CREATED");
        assertThat(pendingItem.teacherName()).isEqualTo("集成测试老师");
    }

    @Test
    @DisplayName("可购买校验只看课程域：下架/未开售的系列不可购买")
    void purchasableOnlyForOnSaleSeries() {
        Optional<SaleSeries> onSale = catalogRepository.findPurchasable(fx.seriesId());
        assertThat(onSale).isPresent();
        assertThat(onSale.orElseThrow().priceCents()).isEqualTo(fx.priceCents());

        jdbc.update("UPDATE t_series SET sale_status = 'OFF_SALE' WHERE id = ?", fx.seriesId());
        assertThat(catalogRepository.findPurchasable(fx.seriesId())).isEmpty();
    }

    private Long insertOnSaleSeries(Long teacherId, String title, long priceCents) {
        jdbc.update("""
                INSERT INTO t_series
                    (teacher_id, title, summary, sort, status, published_at,
                     sale_status, price_cents, currency, sale_updated_at)
                VALUES (?, ?, '第二门测试课程', 2, 'PUBLISHED', CURRENT_TIMESTAMP(3),
                        'ON_SALE', ?, 'CNY', CURRENT_TIMESTAMP(3))
                """, teacherId, title, priceCents);
        Long seriesId = jdbc.queryForObject(
                "SELECT id FROM t_series WHERE title = ?", Long.class, title);
        jdbc.update("INSERT INTO t_series_chapter (series_id, title, sort) VALUES (?, '第一章', 1)", seriesId);
        Long chapterId = jdbc.queryForObject(
                "SELECT id FROM t_series_chapter WHERE series_id = ?", Long.class, seriesId);
        jdbc.update("""
                INSERT INTO t_series_video (chapter_id, title, sort, video_key, media_status, duration_seconds)
                VALUES (?, '视频一', 1, 'it/video2.mp4', 'READY', 60)
                """, chapterId);
        return seriesId;
    }
}
