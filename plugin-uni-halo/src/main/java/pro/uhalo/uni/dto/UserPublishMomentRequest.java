package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.List;

/** 投稿瞬间请求体 */
@Data
public class UserPublishMomentRequest {

    /** 纯文本内容（服务端转 HTML，与博主发布路径一致） */
    private String content;

    /** 图片地址列表（先调上传接口拿到地址，再随本请求提交） */
    private List<String> images;
}
