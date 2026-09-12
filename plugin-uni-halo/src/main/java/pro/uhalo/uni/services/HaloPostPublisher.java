package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestPreflightItemDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 通过 Halo 官方 Console 接口发布文章。
 */
public interface HaloPostPublisher {

    /**
     * 建稿并发布。
     *
     * <p>发布走异步接口 + 轮询确认，正文再长也不会把单次 HTTP 请求拖到超时。</p>
     */
    Mono<HaloPostPublishResultDTO> publish(HaloPostPublishDTO post, int waitSeconds);

    /**
     * 只建稿不发布（用于「只生成不发布」和人工复核场景）。
     */
    Mono<HaloPostPublishResultDTO> draft(HaloPostPublishDTO post);

    /**
     * 把一篇已经建好的 Halo 文章重新发布一次。
     *
     * <p>不涉及正文，只是重新触发发布并等待发布快照就绪，因此补发不需要重新生成内容；
     * 逐篇发布模式下一篇失败时只补那一篇。</p>
     */
    Mono<HaloPostPublishResultDTO> republish(String postName, int waitSeconds);

    /**
     * 发布环境自检：令牌、作者、分类标签能不能用。
     *
     * <p>发布失败时最耗时的就是定位原因，这里把前置条件一次查清并返回逐项结论，
     * 后台页面直接展示，不必翻日志。</p>
     */
    Mono<List<DigestPreflightItemDTO>> preflight(DigestConfigDTO config);
}
