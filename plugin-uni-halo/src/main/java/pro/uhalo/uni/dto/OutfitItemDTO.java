package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutfitItemDTO {

    private String key;

    private String name;

    /** avatar_frame | nick_frame | theme */
    private String type;

    private int points;

    /** 0 = 永久 */
    private int durationDays;

    private String style;

    /**
     * 解锁门槛：累计消费满该金额（单位「元」）后才能兑换，0 = 无门槛。
     *
     * <p>这是「消费里程碑」的载体——把限定装扮挂在累计消费上，用户为了凑门槛会主动多买；
     * 而限定装扮本身是社交可见的（评论区、榜单、我的页、身份中心都会展示），
     * 戴着它等于替站点做宣传。</p>
     *
     * <p>单位取「元」而非「分」是为了站长填起来不易错（与 goods.price 的「分」不同，
     * 那里必须与微信后台一致所以不能换算）：填 30 就是消费满 30 元。</p>
     */
    private int minSpend;
}
