package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 精选筛选：把「有没有封面图」变成可配置的硬性条件。
 *
 * <p>站长开了「只发布带封面图的文章」后，没配到头图的文章不会进入成稿与发布，
 * 而不是发出去一篇没有配图的文章。</p>
 */
public final class DigestSelection {

    private DigestSelection() {
    }

    /** 已解析到头图地址（抓取阶段：RSS 内嵌图 / og:image） */
    public static boolean hasLeadImage(DigestArticleDTO article) {
        return article != null && DigestTextUtils.isUsableImageUrl(article.getImageUrl());
    }

    /** 已成功上传到附件库（发布阶段才成立） */
    public static boolean hasUploadedImage(DigestArticleDTO article) {
        return article != null && DigestTextUtils.isUsableImageUrl(article.getUploadedImage());
    }

    /**
     * 按上限截断；{@code requireImage} 为真时只保留有头图的文章。
     *
     * @return 新列表（不修改入参）
     */
    public static List<DigestArticleDTO> trim(List<DigestArticleDTO> articles, int limit,
        boolean requireImage) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        var candidates = requireImage
            ? articles.stream().filter(DigestSelection::hasLeadImage).toList()
            : articles;
        var size = Math.min(Math.max(0, limit), candidates.size());
        return new ArrayList<>(candidates.subList(0, size));
    }

    /** 只保留已成功上传封面图的文章（发布前最后一道门槛） */
    public static List<DigestArticleDTO> keepUploaded(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        return articles.stream().filter(DigestSelection::hasUploadedImage).toList();
    }

    /** 被「必须有封面图」挡掉的数量，用于日志 */
    public static int missingImageCount(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return 0;
        }
        return (int) articles.stream().filter(article -> !hasLeadImage(article)).count();
    }
}
