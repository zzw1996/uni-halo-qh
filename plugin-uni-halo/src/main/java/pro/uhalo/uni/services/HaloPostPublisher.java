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
     * 保存对已有文章的修改（标题 / 封面 / 分类 / 标签 / 正文 / 是否允许评论）。
     *
     * <p>先把文章整份读回来，只改本插件负责的字段再原样回传：Halo 的更新接口是整体替换，
     * 只发几个字段会把它自己维护的 published 标签、快照名、作者一起抹掉。</p>
     *
     * @param republish   是否在保存后立刻重新发布（让改动在站点上生效）。待审投稿传 false——
     *                    改动只落到 head 快照、前台仍渲染旧的 release 快照，否则
     *                    「先发无害内容、过审后再改成广告」就绕过了审核
     * @param waitSeconds 触发发布后等待发布快照就绪的上限（秒）
     */
    Mono<HaloPostPublishResultDTO> update(String postName, HaloPostPublishDTO post,
        boolean republish, int waitSeconds);

    /** 撤回发布：文章回到草稿态，站点上不再可见（内容与快照都留着，随时可再发布） */
    Mono<Void> unpublish(String postName);

    /**
     * 发布环境自检：令牌、作者、分类标签能不能用。
     *
     * <p>发布失败时最耗时的就是定位原因，这里把前置条件一次查清并返回逐项结论，
     * 后台页面直接展示，不必翻日志。</p>
     */
    Mono<List<DigestPreflightItemDTO>> preflight(DigestConfigDTO config);
}
