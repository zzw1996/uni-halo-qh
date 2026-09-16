package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 日报分类（供小程序端筛选条使用）。
 *
 * <p>只回传<b>本期实际出现过的</b>分类，避免筛选项里全是点进去没内容的空分类。</p>
 */
@Data
@Builder
public class DigestCategoryDTO {

    private String key;

    private String label;

    private String emoji;

    /** 当前返回的数据里属于该分类的篇数 */
    private int count;
}
