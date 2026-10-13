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
     * 抓取<b>任意</b> RSS/Atom 地址（内置源之外的那些）。
     *
     * <p>给「AI 定制内容」的关键词检索用：把 Bing / Google 新闻的检索 RSS 当作源来抓，
     * 解析、按时间窗过滤、去重的口径与内置源完全一致——同一套零件，少一条会走偏的岔路。</p>
     *
     * @param feedUrls 完整地址（已带查询串）
     * @param hours    只看最近多少小时内的条目
     */
    Mono<DigestFeedResultDTO> fetchUrls(List<String> feedUrls, int hours);

    /**
     * 为首图为空的文章补一张头图：抓文章页取 og:image / twitter:image。
     */
    Mono<List<DigestArticleDTO>> resolveLeadImages(List<DigestArticleDTO> articles);
}
