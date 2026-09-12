package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import reactor.core.publisher.Mono;

import java.util.List;

public interface DigestFeedService {

    /**
     * 并发抓取全部内置 RSS 源，按时间窗过滤后返回。
     *
     * <p>单个源失败只跳过该源，不影响整体；时间窗内一篇文章都没有时返回空列表，
     * 由调用方决定是否视为失败。</p>
     */
    Mono<DigestFeedResultDTO> fetchRecent(int hours);

    /**
     * 为首图为空的文章补一张头图：抓文章页取 og:image / twitter:image。
     */
    Mono<List<DigestArticleDTO>> resolveLeadImages(List<DigestArticleDTO> articles);
}
