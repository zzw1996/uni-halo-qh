package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 用积分解锁订阅额度的请求体。
 *
 * <p>{@code count} 省略即 1 份；服务端按配置的上限与单价校验，
 * 并对同一份额度做幂等（重复提交不会扣两次积分）。</p>
 */
@Data
public class SubscribeQuotaRequest {

    private Integer count;
}
