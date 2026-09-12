package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 虚拟支付订单（道具直购）。
 *
 * <p>个人主体小程序仅支持道具直购（mode=short_series_goods），订单对应具体权益
 * （数据看板高级版 / 发布券 / 装扮），不涉及积分充值。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "PayOrder", plural = "payOrders", singular = "payOrder")
public class PayOrder extends AbstractExtension {

    private String openid;

    private String wxUserId;

    /** 商家业务单号（下单时必填、唯一） */
    private String outTradeNo;

    /** 微信平台单号（发货推送/查单返回，幂等去重以此为准） */
    private String wxOrderId;

    private String goodsId;

    private String goodsName;

    /** 道具单价（分），与微信后台道具价格一致 */
    private Long price;

    /**
     * 到账积分（历史字段：仅旧的积分充值订单有值，新订单恒为 null）。
     *
     * <p>保留该字段仅为兼容已落库的历史订单反序列化，发货逻辑不再读取，
     * 个人主体小程序不支持现金充值积分。</p>
     */
    private Integer points;

    /** 道具类型：publish_ticket | outfit（dashboard 仅存在于升级前的历史订单，用于履约） */
    private String goodsType;

    /** 道具数量 */
    private Integer goodsValue;

    /** 装扮 key（type=outfit 时使用） */
    private String goodsStyle;

    private Integer quantity;

    private Integer env;

    private String attach;

    /** pending | delivered | closed */
    private String status;

    private Boolean delivered;

    private String transactionId;

    private String mchOrderNo;

    private Instant paidTime;

    private Instant deliverTime;

    private Instant createTime;

    private Instant updateTime;

    private String remark;
}
