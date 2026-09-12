package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 后台订单列表项：用于人工退款与对账。
 *
 * <p>包含微信平台单号（{@code wxOrderId}）——站长在微信侧发起退款时需要它。</p>
 */
@Data
@Builder
public class AdminPayOrderItemDTO {

    /** 扩展资源名（metadata.name），供后台按需定位单条记录 */
    private String id;

    /** 商家业务单号（下单时生成，唯一） */
    private String outTradeNo;

    /** 微信平台单号（发货推送/查单返回；人工退款凭据） */
    private String wxOrderId;

    private String openid;

    private String nickname;

    private String goodsId;

    private String goodsName;

    /** 道具类型：content_ticket / outfit（历史订单可能为 dashboard / publish_ticket） */
    private String goodsType;

    /** 装扮 key（type=outfit 时使用） */
    private String goodsStyle;

    /** 金额（单位：分） */
    private Long price;

    /** pending | delivered | closed | needs_refund */
    private String status;

    private Boolean delivered;

    private Instant createTime;

    private Instant paidTime;

    private Instant updateTime;

    /** 订单备注（needs_refund 时写明需人工退款的原因） */
    private String remark;
}
