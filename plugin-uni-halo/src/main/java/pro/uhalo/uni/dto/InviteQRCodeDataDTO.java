package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 邀请小程序码数据：imageUrl 为附件存储地址（上传失败可为空），
 * base64 始终返回，前端写入本地临时文件绘制可完全避开域名白名单限制。
 */
@Data
@Builder
public class InviteQRCodeDataDTO {

    /** 绑定的邀请码 */
    private String inviteCode;

    /** 小程序码图片附件地址（可能为空） */
    private String imageUrl;

    /** 小程序码图片 base64（PNG，始终返回） */
    private String base64;
}
