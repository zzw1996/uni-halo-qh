package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.dto.PayStatsDTO;
import pro.uhalo.uni.dto.PayStatsGoodsItemDTO;
import pro.uhalo.uni.dto.PayStatsTrendItemDTO;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.PointsRecord;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 支付数据看板聚合。
 *
 * <p>与 {@code DashboardServiceImpl} 同一做法：全量读出后在内存里聚合。
 * 订单与积分流水在个人站点上是千到万级的量，全量读的代价可以接受，
 * 换来的是不必为每种统计单独建索引、也不必引入聚合框架。</p>
 *
 * <p>口径上刻意区分「订单状态」与「有效收入」：GMV、商品销量、付费用户数
 * 都只算 delivered，其余状态单独计数。混在一起会让看板虚高，
 * 而虚高的数字比没有数字更危险——它会让人做出错误的经营决策。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayStatsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    /** 趋势天数（含今天） */
    private static final int TREND_DAYS = 14;

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_DELIVERED = "delivered";
    private static final String STATUS_CLOSED = "closed";
    private static final String STATUS_NEEDS_REFUND = "needs_refund";

    /** 现金购买阅读券的动作码（与 VirtualPayServiceImpl 保持一致） */
    private static final String ACTION_PURCHASE_TICKET = "purchase_content_ticket";

    private final PayOrderPersistence payOrderPersistence;
    private final PointsPersistence pointsPersistence;

    public Mono<PayStatsDTO> summary() {
        return payOrderPersistence.listAll()
            .collectList()
            // listAllRecords / listAllAccounts 返回的是 Mono<List<T>>（不是 Flux），
            // 因此无需再 collectList，直接用 flatMap 串联
            .flatMap(orders -> pointsPersistence.listAllRecords()
                .flatMap(records -> totalTicketsInHand()
                    .map(ticketsInHand -> build(orders, records, ticketsInHand))))
            .onErrorResume(ex -> {
                log.warn("支付看板统计失败（返回空看板而不是报错，避免整页打不开）", ex);
                return Mono.just(PayStatsDTO.builder()
                    .trend(List.of())
                    .goodsRanking(List.of())
                    .build());
            });
    }

    private static PayStatsDTO build(List<PayOrder> orders, List<PointsRecord> records,
        long ticketsInHand) {
        var delivered = orders.stream()
            .filter(order -> STATUS_DELIVERED.equals(order.getStatus()))
            .toList();

        var grossAmountCents = delivered.stream().mapToLong(PayStatsService::amountOf).sum();
        var payingUsers = delivered.stream()
            .map(PayOrder::getOpenid)
            .filter(Objects::nonNull)
            .distinct()
            .count();
        var tickets = ticketTotals(records);
        var sold = soldCountsOf(delivered);

        return PayStatsDTO.builder()
            .totalOrders(orders.size())
            .deliveredOrders(delivered.size())
            .pendingOrders(countByStatus(orders, STATUS_PENDING))
            .needsRefundOrders(countByStatus(orders, STATUS_NEEDS_REFUND))
            .closedOrders(countByStatus(orders, STATUS_CLOSED))
            .grossAmountCents(grossAmountCents)
            .payingUsers(payingUsers)
            .avgAmountCents(delivered.isEmpty() ? 0L : grossAmountCents / delivered.size())
            .ticketsPurchased(tickets.purchased())
            .ticketsFreeGranted(tickets.freeGranted())
            .ticketsConsumed(tickets.consumed())
            .ticketConsumeRate(tickets.consumeRate())
            .ticketsInHand(ticketsInHand)
            .outfitSold(sold.outfit())
            .membershipSold(sold.membership())
            .contentTicketOrders(sold.contentTicket())
            .trend(buildTrend(delivered))
            .goodsRanking(buildGoodsRanking(delivered))
            .build();
    }

    private static long countByStatus(List<PayOrder> orders, String status) {
        return orders.stream().filter(order -> status.equals(order.getStatus())).count();
    }

    private static long amountOf(PayOrder order) {
        var price = order.getPrice() == null ? 0L : Math.max(0L, order.getPrice());
        var quantity = order.getQuantity() == null ? 1 : Math.max(1, order.getQuantity());
        return price * quantity;
    }

    /** 券的三个口径：现金购买、免费赠送、已消耗。 */
    private record TicketTotals(long purchased, long freeGranted, long consumed, long consumeRate) {
    }

    /**
     * 从积分流水的 {@code tickets} 字段汇总券的发放与消耗。
     *
     * <p>现金买券与免费赠券必须分开统计：两者的成本结构完全不同，
     * 合起来看会让人误判「券都是花钱买的」或「券都是白送的」。</p>
     *
     * <p>实现注意：builder 是链式不可变对象，循环里必须把新对象放回 Map，
     * 只 add 到 list 会丢掉「第二笔订单之后」的累加。</p>
     */
    private static TicketTotals ticketTotals(List<PointsRecord> records) {
        long purchased = 0;
        long grantedFree = 0;
        long consumed = 0;
        for (var record : records) {
            var tickets = record.getTickets();
            if (tickets == null || tickets == 0) {
                continue;
            }
            if (tickets > 0) {
                if (ACTION_PURCHASE_TICKET.equals(record.getAction())) {
                    purchased += tickets;
                } else {
                    grantedFree += tickets;
                }
            } else {
                consumed += -tickets;
            }
        }
        var issued = purchased + grantedFree;
        var rate = issued <= 0 ? 0L : Math.round(consumed * 100.0 / issued);
        return new TicketTotals(purchased, grantedFree, consumed, rate);
    }

    /**
     * 全部账户当前持有的券合计。
     *
     * <p>与「发放 - 消耗」互为校验：两个数字差距很大说明有券在账户上沉淀，
     * 要么是用户拿了不用（赠券力度过头），要么是某处记账漏了。</p>
     */
    private Mono<Long> totalTicketsInHand() {
        return pointsPersistence.listAllAccounts()
            .flatMapMany(reactor.core.publisher.Flux::fromIterable)
            .map(account -> account.getContentTickets() == null ? 0L
                : Math.max(0L, account.getContentTickets()))
            .reduce(0L, Long::sum)
            .onErrorResume(ex -> {
                log.warn("统计券余额合计失败，按 0 处理：{}", ex.getMessage());
                return Mono.just(0L);
            });
    }

    /** 已到货订单按商品类型的销量。 */
    private record SoldCounts(long outfit, long membership, long contentTicket) {
    }

    private static SoldCounts soldCountsOf(List<PayOrder> delivered) {
        long outfit = 0;
        long membership = 0;
        long contentTicket = 0;
        for (var order : delivered) {
            var type = order.getGoodsType() == null ? "" : order.getGoodsType();
            switch (type) {
                case "content_ticket" -> contentTicket++;
                case "outfit" -> outfit++;
                case "membership" -> membership++;
                default -> {
                    // bundle 等类型无法归到单一权益：已计入总额与商品排行，此处不重复计数
                }
            }
        }
        return new SoldCounts(outfit, membership, contentTicket);
    }

    /**
     * 近 14 天趋势（含今天，升序）。
     *
     * <p>没有订单的日期也要补 0：趋势图缺日期会让折线断成几段，
     * 看起来像「那几天系统坏了」。</p>
     */
    private static List<PayStatsTrendItemDTO> buildTrend(List<PayOrder> delivered) {
        var today = LocalDate.now(ZONE);
        var buckets = new LinkedHashMap<LocalDate, long[]>();
        for (var i = TREND_DAYS - 1; i >= 0; i--) {
            buckets.put(today.minusDays(i), new long[] {0, 0});
        }
        for (var order : delivered) {
            var day = orderDay(order);
            var bucket = day == null ? null : buckets.get(day);
            if (bucket == null) {
                continue;
            }
            bucket[0]++;
            bucket[1] += amountOf(order);
        }
        var trend = new ArrayList<PayStatsTrendItemDTO>(buckets.size());
        buckets.forEach((day, bucket) -> trend.add(PayStatsTrendItemDTO.builder()
            .date(day.format(DAY_FMT))
            .count(bucket[0])
            .amountCents(bucket[1])
            .build()));
        return List.copyOf(trend);
    }

    /**
     * 订单归属日期：优先付款时间，缺失时退回创建时间。
     *
     * <p>用创建时间兜底是必要的——历史订单里 paidTime 可能为空，
     * 直接跳过会让趋势图的合计与上方 GMV 对不上，站长会以为统计坏了。</p>
     */
    private static LocalDate orderDay(PayOrder order) {
        var instant = order.getPaidTime() != null ? order.getPaidTime() : order.getCreateTime();
        return instant == null ? null : instant.atZone(ZONE).toLocalDate();
    }

    /** 商品维度排行：按已到货金额倒序，同额按订单数倒序。 */
    private static List<PayStatsGoodsItemDTO> buildGoodsRanking(List<PayOrder> delivered) {
        var grouped = new LinkedHashMap<String, PayStatsGoodsItemDTO>();
        for (var order : delivered) {
            var key = order.getGoodsId() == null ? "" : order.getGoodsId();
            var existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, PayStatsGoodsItemDTO.builder()
                    .goodsId(key)
                    .goodsName(order.getGoodsName())
                    .goodsType(order.getGoodsType())
                    .orderCount(1)
                    .amountCents(amountOf(order))
                    .build());
                continue;
            }
            grouped.put(key, PayStatsGoodsItemDTO.builder()
                .goodsId(key)
                // 商品名取最近遍历到的一条：站长改过名称后看板应显示新名字
                .goodsName(order.getGoodsName() != null
                    ? order.getGoodsName() : existing.getGoodsName())
                .goodsType(existing.getGoodsType())
                .orderCount(existing.getOrderCount() + 1)
                .amountCents(existing.getAmountCents() + amountOf(order))
                .build());
        }
        return grouped.values().stream()
            .sorted(Comparator.comparingLong(PayStatsGoodsItemDTO::getAmountCents)
                .thenComparingLong(PayStatsGoodsItemDTO::getOrderCount)
                .reversed())
            .toList();
    }
}
