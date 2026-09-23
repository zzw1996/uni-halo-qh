package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 邀请分享落地页数据。
 *
 * <p><b>只给"这个人订阅了哪些分类"和他是谁，不给他的关键词与来源</b>：分类是站点公开的分类体系，
 * 而关键词/来源更接近个人兴趣标签，传出去像在泄露别人的订阅习惯。文章列表也不在这里返回——
 * 小程序直接按分类调站点的内容接口取（与首页同一套口径），既省一次转手，也天然复用阅读/积分/付费判定。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteLandingDTO {

    /** 邀请码是否有效；false 时前端展示 message 并回落站内最新 */
    private boolean valid;

    /** 无效原因 / 给访问者的说明（例如"这位读者还没订阅分类"） */
    private String message;

    private String inviteCode;

    /** 分享人昵称与头像（站内公开信息，用于"XX 为你精选"） */
    private String sharerName;

    private String sharerAvatar;

    /** 分享人订阅的分类（metadata.name + 中文显示名） */
    private List<CategoryItem> categories;

    /** 访问者是否已登录（未登录时前端引导"登录后自动关注这些分类"） */
    private boolean loggedIn;

    /** 访问者是否已有自己的订阅；有则引导语换成「也关注这些」，且不覆盖他已有的 */
    private boolean hasOwnSubscription;

    /** 访问者当前生效的分类（自己订的 + 已继承的），用于把已关注的标签标灰 */
    private List<String> followedCategories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryItem {

        /** 站点分类 metadata.name */
        private String name;

        /** 中文显示名；取不到时回落为 name */
        private String label;
    }
}
