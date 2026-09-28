package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestConfigDTO;
import reactor.core.publisher.Mono;

/**
 * 日报头图上传：走 Halo 官方附件接口，图片会正常出现在博客附件库里。
 */
public interface DigestAssetUploader {

    /**
     * 把一张远程图片转存为 Halo 附件，返回可访问地址。
     *
     * <p>优先调用官方的「从 URL 转存」接口（Halo 服务端拉取），失败时退回
     * 「插件下载 + multipart 上传」。两条路都不通时以错误结束，由调用方决定是否跳过该图。</p>
     *
     * @param sourceUrl 图片原始地址
     * @param baseName  附件文件名前缀（不含扩展名）
     */
    Mono<String> uploadImage(String sourceUrl, String baseName, DigestConfigDTO config);

    /**
     * 把插件本地生成的图片（标题卡片封面）上传为 Halo 附件，返回可访问地址。
     *
     * <p>只有一条路（multipart 上传），没有「交给 Halo 去拉」这回事——字节已经在手上。
     * 存储策略与分组解析、地址绝对化的口径与 {@link #uploadImage} 完全一致，
     * 否则会出现「图片传上去了却被判成没有封面」的老问题。</p>
     *
     * @param bytes     图片字节
     * @param mediaType 图片类型（如 {@code image/png}）
     * @param baseName  附件文件名前缀（不含扩展名）
     */
    Mono<String> uploadBytes(byte[] bytes, String mediaType, String baseName,
        DigestConfigDTO config);
}
