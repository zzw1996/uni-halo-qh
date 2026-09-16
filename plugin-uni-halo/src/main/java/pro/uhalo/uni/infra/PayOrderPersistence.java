package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.PayOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.util.List;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 虚拟支付订单持久化：写入 Halo Extension 存储（底层数据库）。
 */
@Component
@RequiredArgsConstructor
public class PayOrderPersistence {

    private final ReactiveExtensionClient client;

    public Mono<PayOrder> create(PayOrder order) {
        return client.create(order);
    }

    public Mono<PayOrder> update(PayOrder order) {
        return client.update(order);
    }

    public Mono<PayOrder> findByOutTradeNo(String outTradeNo) {
        return findOneByField("outTradeNo", outTradeNo);
    }

    public Mono<PayOrder> findByWxOrderId(String wxOrderId) {
        return findOneByField("wxOrderId", wxOrderId);
    }

    public Mono<List<PayOrder>> listPendingOlderThan(Instant threshold) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("status", "pending")));
        return client.listAll(PayOrder.class, listOptions, null)
            .filter(order -> order.getCreateTime() == null || order.getCreateTime().isBefore(threshold))
            .collectList();
    }

    public Flux<PayOrder> listAll() {
        return client.listAll(PayOrder.class, new ListOptions(), null);
    }

    /**
     * 按 openid 列出该用户的全部订单（走 openid 单字段索引，不全表扫描）。
     *
     * <p>「我的订单」与「累计消费」都基于它。调用方自行排序与分页——
     * 用户量级下订单条数有限，且排序口径（创建时间倒序）只在展示层需要。</p>
     */
    public Flux<PayOrder> listByOpenid(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("openid", openid)));
        return client.listAll(PayOrder.class, listOptions, null);
    }

    private Mono<PayOrder> findOneByField(String indexField, String value) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal(indexField, value)));
        return client.listAll(PayOrder.class, listOptions, null).next();
    }

    public static PayOrder newOrder(String openid, String wxUserId, String outTradeNo,
        String goodsId, String goodsName, long price, int quantity, int env,
        String attach) {
        var order = new PayOrder();
        order.setOpenid(openid);
        order.setWxUserId(wxUserId);
        order.setOutTradeNo(outTradeNo);
        order.setGoodsId(goodsId);
        order.setGoodsName(goodsName);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setEnv(env);
        order.setAttach(attach);
        order.setStatus("pending");
        order.setDelivered(false);
        order.setCreateTime(Instant.now());
        order.setUpdateTime(Instant.now());
        var metadata = new Metadata();
        metadata.setName("po-" + outTradeNo.toLowerCase());
        metadata.setCreationTimestamp(Instant.now());
        order.setMetadata(metadata);
        return order;
    }
}
