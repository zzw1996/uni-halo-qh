package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 虚拟支付公开配置（不含 AppKey 等敏感信息）。
 */
@Data
@Builder
public class PayPublicConfigDTO {

    private boolean enabled;

    private List<PayGoodsDTO> goods;
}
