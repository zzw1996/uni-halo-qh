package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 虚拟支付订单（道具直购：积分充值）。
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

    /** 到账积分（1 个道具 = points 分；仅旧积分充值模式使用） */
    private Integer points;

    /** 道具类型：points | dashboard | publish_ticket | outfit */
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
