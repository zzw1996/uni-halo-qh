package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 旧文章归类的写入结果：逐条回报，成功的和跳过的分开说清楚。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostClassifyApplyResponse {

    /** 成功写入的篇数 */
    private int applied;

    /** 跳过的篇数（文章已不存在、已归过类、内容为空等） */
    private int skipped;

    /** 失败的篇数（写入冲突等，可再点一次重试） */
    private int failed;

    /** 逐条结果，页面直接列出来，避免「一共成功 3 篇」这种看不出是哪 3 篇的回报 */
    private List<Item> items;

    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        private String name;

        private String title;

        /** APPLIED / SKIPPED / FAILED */
        private String state;

        /** 实际写入的分类显示名 */
        private String category;

        /** 实际写入的标签名 */
        private List<String> tags;

        private String message;
    }
}
