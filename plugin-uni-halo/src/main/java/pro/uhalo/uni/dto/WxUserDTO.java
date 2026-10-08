package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WxUserDTO {

    private String id;

    private String openid;

    // ⚠️ 这里刻意没有 sessionKey 字段，请不要加回来。
    // sessionKey 是微信会话密钥（虚拟支付 signature 的 HMAC 密钥），只属于服务端：
    //   - 签名在服务端完成（VirtualPayServiceImpl -> XPaySigner），客户端不需要它；
    //   - 一旦随登录响应下发，任何能读到该响应的人都能伪造该用户的支付签名。
    // 会话密钥仍存在 WxUser（scheme）里，服务端按 openid 取用。
    // 客户端也一直不持久化它，见小程序 utils/auth.js 的 `delete toStore.sessionKey`。

    private String unionid;

    private String nickname;

    private String avatarUrl;

    private Boolean profileComplete;

    private Integer profileModifyCount;

    private String haloUserName;

    private String haloBindStatus;
}
