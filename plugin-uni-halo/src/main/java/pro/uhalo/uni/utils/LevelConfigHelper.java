package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.LevelConfigDTO;
import pro.uhalo.uni.dto.LevelItemDTO;
import pro.uhalo.uni.dto.OutfitItemDTO;
import pro.uhalo.uni.dto.TitleItemDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 等级/称号/装扮配置解析：来自设置分组 levelConfig。
 * 等级按「累计获得积分」动态计算；称号按条件自动解锁；装扮需积分兑换。
 */
public final class LevelConfigHelper {

    public static final String GROUP_NAME = "levelConfig";

    public static final Set<String> TITLE_TYPES =
        Set.of("total_browse", "sign_in", "vote", "comment", "invite", "total_points");

    public static final Set<String> OUTFIT_TYPES = Set.of("avatar_frame", "nick_frame", "theme");

    private LevelConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static LevelConfigDTO defaults() {
        return LevelConfigDTO.builder()
            .enabled(true)
            .levels(List.of(
                seedLevel(1, 0, "新手上路"),
                seedLevel(2, 100, "初露锋芒"),
                seedLevel(3, 300, "小有名气"),
                seedLevel(4, 600, "活跃达人"),
                seedLevel(5, 1000, "内容猎人"),
                seedLevel(6, 1600, "资深玩家"),
                seedLevel(7, 2400, "人气之星"),
                seedLevel(8, 3500, "社区传奇"),
                seedLevel(9, 5000, "积分大亨"),
                seedLevel(10, 7000, "至尊VIP")
            ))
            .titles(List.of(
                seedTitle("read_100", "百读不厌", "total_browse", 100, "累计浏览 100 篇文章"),
                seedTitle("sign_30", "风雨无阻", "sign_in", 30, "累计签到 30 天"),
                seedTitle("vote_50", "民意代表", "vote", 50, "累计参与 50 次投票"),
                seedTitle("comment_50", "妙语连珠", "comment", 50, "累计发表 50 条评论"),
                seedTitle("invite_3", "星火燎原", "invite", 3, "邀请 3 位好友注册"),
                seedTitle("points_5000", "积分大亨", "total_points", 5000, "累计获得 5000 积分")
            ))
            .outfits(List.of(
                seedOutfit("avatar_gold", "金色头像框", "avatar_frame", 200, 0, "gold"),
                seedOutfit("avatar_rainbow", "彩虹头像框", "avatar_frame", 500, 0, "rainbow"),
                seedOutfit("nick_star", "星光辉映", "nick_frame", 100, 0, "⭐"),
                seedOutfit("nick_crown", "王者之冠", "nick_frame", 300, 0, "👑"),
                seedOutfit("theme_dusk", "蓝紫渐变主题", "theme", 150, 0, "#667eea,#764ba2"),
                seedOutfit("theme_sunset", "落日橙主题", "theme", 150, 0, "#f6d365,#fda085")
            ))
            .build();
    }

    private static LevelItemDTO seedLevel(int level, int minPoints, String title) {
        return LevelItemDTO.builder().level(level).minPoints(minPoints).title(title).build();
    }

    private static TitleItemDTO seedTitle(String id, String name, String type, int target, String desc) {
        return TitleItemDTO.builder().id(id).name(name).type(type).target(target).desc(desc).build();
    }

    private static OutfitItemDTO seedOutfit(String key, String name, String type, int points,
        int durationDays, String style) {
        return OutfitItemDTO.builder()
            .key(key).name(name).type(type).points(points).durationDays(durationDays).style(style)
            .build();
    }

    public static LevelConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var defaults = defaults();
        var levels = new ArrayList<LevelItemDTO>();
        var titles = new ArrayList<TitleItemDTO>();
        var outfits = new ArrayList<OutfitItemDTO>();
        parseLevels(node.path("levels"), levels);
        parseTitles(node.path("titles"), titles);
        parseOutfits(node.path("outfits"), outfits);
        // 配置为空时回落种子，保证功能可用
        if (levels.isEmpty()) {
            levels.addAll(defaults.getLevels());
        }
        if (titles.isEmpty()) {
            titles.addAll(defaults.getTitles());
        }
        if (outfits.isEmpty()) {
            outfits.addAll(defaults.getOutfits());
        }
        levels.sort(Comparator.comparingInt(LevelItemDTO::getLevel));
        return LevelConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(true))
            .levels(List.copyOf(levels))
            .titles(List.copyOf(titles))
            .outfits(List.copyOf(outfits))
            .build();
    }

    private static void parseLevels(JsonNode node, List<LevelItemDTO> sink) {
        if (!node.isArray()) {
            return;
        }
        node.forEach(item -> {
            var level = item.path("level").asInt(0);
            var minPoints = item.path("minPoints").asInt(0);
            var title = item.path("title").asText("").trim();
            if (level > 0 && minPoints >= 0 && !title.isEmpty()) {
                sink.add(LevelItemDTO.builder().level(level).minPoints(minPoints).title(title).build());
            }
        });
    }

    private static void parseTitles(JsonNode node, List<TitleItemDTO> sink) {
        if (!node.isArray()) {
            return;
        }
        node.forEach(item -> {
            var id = item.path("id").asText("").trim();
            var name = item.path("name").asText("").trim();
            var type = item.path("type").asText("").trim().toLowerCase();
            var target = item.path("target").asInt(0);
            if (!id.isEmpty() && !name.isEmpty() && TITLE_TYPES.contains(type) && target > 0) {
                sink.add(TitleItemDTO.builder()
                    .id(id).name(name).type(type).target(target)
                    .desc(item.path("desc").asText(""))
                    .build());
            }
        });
    }

    private static void parseOutfits(JsonNode node, List<OutfitItemDTO> sink) {
        if (!node.isArray()) {
            return;
        }
        node.forEach(item -> {
            var key = item.path("key").asText("").trim();
            var name = item.path("name").asText("").trim();
            var type = item.path("type").asText("").trim().toLowerCase();
            var points = item.path("points").asInt(0);
            if (!key.isEmpty() && !name.isEmpty() && OUTFIT_TYPES.contains(type) && points > 0) {
                sink.add(OutfitItemDTO.builder()
                    .key(key).name(name).type(type).points(points)
                    .durationDays(Math.max(0, item.path("durationDays").asInt(0)))
                    .style(item.path("style").asText(""))
                    .build());
            }
        });
    }
}
