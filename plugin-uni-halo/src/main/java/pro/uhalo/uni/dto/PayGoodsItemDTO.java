package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 套餐（礼包）里的单个权益项。
 *
 * <p>用途：一条现金商品同时包含多种权益，例如「会员 30 天 + 5 张阅读券」
 * 或「主题 + 头像框」装扮套装。</p>
 */
@Data
@Builder
public class PayGoodsItemDTO {

    /** content_ticket（阅读券）| outfit（装扮）| membership（会员天数） */
    private String type;

    /**
     * 数量：content_ticket = 券张数；membership = 天数；outfit 固定 1。
     */
    private Integer value;

    /** 装扮 key（type=outfit 时必填，对应 levelConfig.outfits.key） */
    private String style;
}
