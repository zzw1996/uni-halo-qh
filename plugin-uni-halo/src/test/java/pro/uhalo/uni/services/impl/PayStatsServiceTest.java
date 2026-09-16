package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 支付看板聚合的口径测试。
 *
 * <p>锁住的核心是「有效收入」的定义：GMV、商品销量、付费用户数只算已到货订单。
 * 把待支付与需退款也算进去会让看板虚高，而虚高的数字会直接导致错误的经营决策
 * （例如据此提价、或误判某档位卖得好）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PayStatsServiceTest {

    @Mock
    private PayOrderPersistence payOrderPersistence;

    @Mock
    private PointsPersistence pointsPersistence;

    @InjectMocks
    private PayStatsService service;

    private void stub(List<PayOrder> orders, List<PointsRecord> records, List<UserPoints> accounts) {
        when(payOrderPersistence.listAll()).thenReturn(Flux.fromIterable(orders));
        when(pointsPersistence.listAllRecords()).thenReturn(Mono.just(records));
        when(pointsPersistence.listAllAccounts()).thenReturn(Mono.just(accounts));
    }

    private static PayOrder order(String status, String openid, String goodsId, String type,
        long price) {
        var order = new PayOrder();
        order.setStatus(status);
        order.setOpenid(openid);
        order.setGoodsId(goodsId);
        order.setGoodsName("商品" + goodsId);
        order.setGoodsType(type);
        order.setPrice(price);
        order.setQuantity(1);
        order.setPaidTime(Instant.now());
        order.setCreateTime(Instant.now());
        return order;
    }

    private static PointsRecord ticketRecord(String action, int tickets) {
        var record = new PointsRecord();
        record.setAction(action);
        record.setTickets(tickets);
        record.setCreateTime(Instant.now());
        return record;
    }

    @Test
    void shouldCountOnlyDeliveredOrdersAsRevenue() {
        stub(List.of(
            order("delivered", "u1", "g1", "content_ticket", 600L),
            order("delivered", "u1", "g2", "outfit", 900L),
            order("pending", "u2", "g1", "content_ticket", 600L),
            order("needs_refund", "u3", "g2", "outfit", 900L),
            order("closed", "u4", "g1", "content_ticket", 600L)
        ), List.of(), List.of());

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                assertThat(stats.getTotalOrders()).isEqualTo(5);
                assertThat(stats.getDeliveredOrders()).isEqualTo(2);
                assertThat(stats.getPendingOrders()).isEqualTo(1);
                assertThat(stats.getNeedsRefundOrders()).isEqualTo(1);
                assertThat(stats.getClosedOrders()).isEqualTo(1);
                // 只有已到货的 600 + 900 计入 GMV
                assertThat(stats.getGrossAmountCents()).isEqualTo(1500L);
                assertThat(stats.getAvgAmountCents()).isEqualTo(750L);
                // 同一用户的两笔订单只算一个付费用户
                assertThat(stats.getPayingUsers()).isEqualTo(1);
                assertThat(stats.getContentTicketOrders()).isEqualTo(1);
                assertThat(stats.getOutfitSold()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void shouldSeparatePurchasedAndFreeTickets() {
        var records = new ArrayList<PointsRecord>();
        // 现金买券 5 张
        records.add(ticketRecord("purchase_content_ticket", 5));
        // 免费赠券：新人 1 + 签到累计 4
        records.add(ticketRecord("ticket_new_user", 1));
        records.add(ticketRecord("ticket_sign_in", 4));
        // 消耗 6 张（负数）
        records.add(ticketRecord("unlock_content", -6));
        // 与券无关的流水不应影响统计
        records.add(ticketRecord("browse", 0));
        var pointsOnly = new PointsRecord();
        pointsOnly.setAction("browse");
        pointsOnly.setPoints(10);
        records.add(pointsOnly);

        stub(List.of(), records, List.of(account(4), account(0)));

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                assertThat(stats.getTicketsPurchased()).isEqualTo(5);
                assertThat(stats.getTicketsFreeGranted()).isEqualTo(5);
                assertThat(stats.getTicketsConsumed()).isEqualTo(6);
                // 6 / (5 + 5) = 60%
                assertThat(stats.getTicketConsumeRate()).isEqualTo(60);
                assertThat(stats.getTicketsInHand()).isEqualTo(4);
            })
            .verifyComplete();
    }

    @Test
    void shouldHandleZeroIssuedTicketsWithoutDivideByZero() {
        stub(List.of(), List.of(ticketRecord("unlock_content", -2)), List.of());

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                assertThat(stats.getTicketsConsumed()).isEqualTo(2);
                assertThat(stats.getTicketConsumeRate()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void shouldRankGoodsByDeliveredAmountAndKeyByGoodsId() {
        stub(List.of(
            order("delivered", "u1", "g1", "content_ticket", 600L),
            order("delivered", "u2", "g1", "content_ticket", 600L),
            order("delivered", "u3", "g2", "outfit", 900L),
            order("pending", "u4", "g2", "outfit", 900L)
        ), List.of(), List.of());

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                var ranking = stats.getGoodsRanking();
                assertThat(ranking).hasSize(2);
                // g1 两笔共 1200 分 > g2 一笔 900 分
                assertThat(ranking.get(0).getGoodsId()).isEqualTo("g1");
                assertThat(ranking.get(0).getOrderCount()).isEqualTo(2);
                assertThat(ranking.get(0).getAmountCents()).isEqualTo(1200L);
                assertThat(ranking.get(1).getGoodsId()).isEqualTo("g2");
                assertThat(ranking.get(1).getAmountCents()).isEqualTo(900L);
            })
            .verifyComplete();
    }

    /** 近 14 天趋势固定返回 14 个点：缺日会让折线看起来像断档。 */
    @Test
    void trendShouldAlwaysCoverFourteenDays() {
        stub(List.of(order("delivered", "u1", "g1", "content_ticket", 600L)),
            List.of(), List.of());

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                assertThat(stats.getTrend()).hasSize(14);
                // 最后一格是今天，且今天的金额被计入
                assertThat(stats.getTrend().get(13).getAmountCents()).isEqualTo(600L);
                assertThat(stats.getTrend().get(13).getCount()).isEqualTo(1);
            })
            .verifyComplete();
    }

    /** 统计失败时返回空看板而不是报错：看板打不开比数字不全更影响使用。 */
    @Test
    void shouldReturnEmptyDashboardOnFailure() {
        // 订单读取就失败：后续的流水/账户读取根本不会被订阅，因此不桩它们
        when(payOrderPersistence.listAll())
            .thenReturn(Flux.error(new IllegalStateException("db down")));

        StepVerifier.create(service.summary())
            .assertNext(stats -> {
                assertThat(stats.getTotalOrders()).isZero();
                assertThat(stats.getTrend()).isEmpty();
                assertThat(stats.getGoodsRanking()).isEmpty();
            })
            .verifyComplete();
    }

    private static UserPoints account(int tickets) {
        var account = new UserPoints();
        account.setContentTickets(tickets);
        return account;
    }
}
