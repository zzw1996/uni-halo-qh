package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 旧文章归类的应用请求体：只包含站长在预览表里勾选采纳的条目。
 *
 * <p>由页面上送而不是服务端重跑一遍判定，原因有两个：重跑要再花一次 AI 调用；
 * 更重要的是站长否掉的条目不该被偷偷写回去。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostClassifyApplyRequest {

    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 文章 metadata.name */
        private String name;

        /** 采纳的主题 key；留空表示只采纳关键词、不归主题 */
        private String category;

        /** 采纳的关键词（落库时自动建标签，去重） */
        private List<String> keywords;
    }
}
