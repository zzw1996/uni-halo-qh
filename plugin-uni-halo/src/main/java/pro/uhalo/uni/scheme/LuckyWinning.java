package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 幸运礼盒中奖单：抽中实物奖品后生成，用户填写收货信息，站长按单发货。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "LuckyWinning", plural = "luckyWinnings", singular = "luckyWinning")
public class LuckyWinning extends AbstractExtension {

    private String wxOpenid;

    private String wxUserId;

    private String prizeName;

    private String emoji;

    /** pending（待填写）| submitted（已提交待发货）| shipped（已发货） */
    private String status;

    private String contactName;

    private String phone;

    private String address;

    private Instant winTime;

    private Instant submitTime;

    /** 站长发货时填写的物流信息（已发货后生效） */
    private String expressCompany;

    private String expressNo;

    private Instant shipTime;

    private String remark;
}
