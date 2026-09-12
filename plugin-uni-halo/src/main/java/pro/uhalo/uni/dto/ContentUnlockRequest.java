package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 阅读券解锁请求。
 */
@Data
public class ContentUnlockRequest {

    /**
     * 文章 metadata.name。
     *
     * <p>必须是 metadata.name 而不是 slug：小程序所有跳转详情页的入口传的都是
     * {@code post.metadata.name}，内容拦截 Filter 也用同一口径，
     * 两侧不一致会导致「扣了券但拦截仍判定未解锁」。</p>
     */
    private String articleName;
}
