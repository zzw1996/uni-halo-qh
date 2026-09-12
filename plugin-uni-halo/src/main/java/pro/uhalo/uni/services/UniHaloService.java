package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.dto.InviteQRCodeDataDTO;
import reactor.core.publisher.Mono;
import java.util.Map;

/**
 * UniHalo 基础服务接口
 *
 * @author 小莫唐尼
 * @since 1.0.0
 */
public interface UniHaloService {
    /**
     * 获取移动端的所有配置
     *
     * @return 配置
     */
    Mono<Map<String, JsonNode>> getAppConfigs();

    /*
     * 根据分组名称获取移动端的配置
     *
     * @param groupName 分组名称
     * @return 配置
     */
    Mono<JsonNode> getAppConfigsByGroupName(String groupName);

    /**
     * 解析并返回二维码图片 URL（不存在则生成）
     */
    Mono<String> resolveQRCodeImageUrl(String postId);

    /**
     * 生成邀请场景小程序码数据（scene = "inv:" + 邀请码，内存缓存）。
     * imageUrl 为附件地址（上传失败可能为空），base64 始终返回。
     */
    Mono<InviteQRCodeDataDTO> resolveInviteQRCodeData(String inviteCode);

    /** 上传图片字节到附件存储，返回外部可访问地址 */
    Mono<String> uploadImageToAttachments(byte[] bytes, String safeName);

    /**
     * 获取二维码信息
     *
     * @param serverRequest:
     * @return: reactor.core.publisher.Mono<org.springframework.web.reactive.function.server.ServerResponse>
     * @author: lywq
     * @date: 2024/08/01 16:27
     **/
    Mono<ServerResponse> getQRCodeInfo(ServerRequest serverRequest);
}
