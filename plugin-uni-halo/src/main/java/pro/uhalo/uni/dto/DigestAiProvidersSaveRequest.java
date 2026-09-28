package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.List;

/**
 * 保存大模型通道清单的请求体。
 *
 * <p>只提交清单本身：服务端在配置存储上做读-改-写合并，其余配置字段（时间窗、精选篇数、
 * 分类清单…）原样保留。这样「保存通道」不会顺手把别的配置改成默认值。</p>
 *
 * <p>每条的 {@code apiKey} 允许是掩码串（页面原样回传）= 不改这把 Key；
 * 传空字符串同样按「不改」处理，要清空请删掉整行。</p>
 */
@Data
public class DigestAiProvidersSaveRequest {

    private List<DigestAiProviderDTO> providers;
}
