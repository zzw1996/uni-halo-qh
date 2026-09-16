package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.List;

/**
 * 保存日报订阅偏好的请求。
 *
 * <p>这是匿名可写的接口，因此服务端必须对数量与长度做硬限制
 * （见 DigestSubscriptionService 的校验），否则任何人都能塞进一份巨大的 JSON。</p>
 */
@Data
public class DigestSubscriptionRequest {

    private Boolean enabled;

    private List<String> categories;

    private List<String> keywords;

    private List<String> sources;

    /** 关注的站点分类（作用于首页主列表与分类页） */
    private List<String> postCategories;
}
