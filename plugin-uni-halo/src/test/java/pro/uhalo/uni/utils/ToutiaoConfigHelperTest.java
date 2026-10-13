package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 今日头条号配置解析：默认值、边界收敛与「风格清单留空 = 内置四种」的口径。
 */
class ToutiaoConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptyConfigShouldFallBackToUsableDefaults() {
        var config = ToutiaoConfigHelper.parse(MAPPER.createObjectNode());

        // 管理员页面：默认开启（误开不会泄露给任何小程序用户）
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getWords()).isEqualTo(1500);
        assertThat(config.getTitleCount()).isEqualTo(3);
        assertThat(config.getStyles()).containsExactly("资讯综述", "深度解读", "干货教程", "情感故事");
        assertThat(config.getDefaultStyle()).isEqualTo("资讯综述");
    }

    @Test
    void missingNodeShouldFallBackToDefaults() {
        assertThat(ToutiaoConfigHelper.parse(null).getWords()).isEqualTo(1500);
    }

    @Test
    void wordsAndTitleCountShouldBeClamped() {
        var node = MAPPER.createObjectNode();
        node.put("words", 99999);
        node.put("titleCount", 99);
        var high = ToutiaoConfigHelper.parse(node);
        assertThat(high.getWords()).isLessThanOrEqualTo(3000);
        assertThat(high.getTitleCount()).isEqualTo(ToutiaoPromptHelper.MAX_TITLES);

        node.put("words", 1);
        node.put("titleCount", 0);
        var low = ToutiaoConfigHelper.parse(node);
        assertThat(low.getWords()).isGreaterThanOrEqualTo(300);
        assertThat(low.getTitleCount()).isEqualTo(1);
    }

    @Test
    void stylesShouldBeSplitDedupedAndTrimmed() {
        var node = MAPPER.createObjectNode();
        node.put("styles", "深度解读，干货教程、深度解读;资讯综述\n情感故事");
        var config = ToutiaoConfigHelper.parse(node);

        assertThat(config.getStyles()).containsExactly("深度解读", "干货教程", "资讯综述", "情感故事");
    }

    @Test
    void defaultStyleOutsideListShouldFallBackToFirst() {
        var node = MAPPER.createObjectNode();
        node.put("styles", "深度解读,干货教程");
        node.put("defaultStyle", "清单外的风格");
        var config = ToutiaoConfigHelper.parse(node);

        assertThat(config.getDefaultStyle()).isEqualTo("深度解读");
    }

    @Test
    void defaultStyleInsideListShouldBeKept() {
        var node = MAPPER.createObjectNode();
        node.put("styles", "深度解读,干货教程");
        node.put("defaultStyle", "干货教程");

        assertThat(ToutiaoConfigHelper.parse(node).getDefaultStyle()).isEqualTo("干货教程");
    }

    @Test
    void blankStylesShouldFallBackToBuiltIn() {
        var node = MAPPER.createObjectNode();
        node.put("styles", "   ");

        assertThat(ToutiaoConfigHelper.parse(node).getStyles())
            .containsExactly("资讯综述", "深度解读", "干货教程", "情感故事");
    }

    @Test
    void voiceShouldDefaultToBuiltInPersona() {
        var config = ToutiaoConfigHelper.parse(MAPPER.createObjectNode());

        // 口吻是「去 AI 味」的关键项：默认给第一人称的普通人视角，不做成空值
        assertThat(config.getVoice()).isEqualTo(ToutiaoPromptHelper.DEFAULT_VOICE);
        assertThat(config.getBannedPhrases()).isEmpty();
    }

    @Test
    void voiceAndBannedPhrasesShouldBeKeptAndTrimmed() {
        var node = MAPPER.createObjectNode();
        node.put("voice", "  一个冷静的行业观察者  ");
        node.put("bannedPhrases", " 家人们, 宝子们 ");
        var config = ToutiaoConfigHelper.parse(node);

        assertThat(config.getVoice()).isEqualTo("一个冷静的行业观察者");
        assertThat(config.getBannedPhrases()).isEqualTo("家人们, 宝子们");
    }

    @Test
    void imageDefaultsShouldBeUsableWithoutImageModel() {
        var config = ToutiaoConfigHelper.parse(MAPPER.createObjectNode());

        // 默认 3 张、用文案卡片（图片模型留空）：这是「开箱就有配图」的口径
        assertThat(config.getImageCount()).isEqualTo(ToutiaoImageHelper.MIN_IMAGES);
        assertThat(config.getImageModel()).isEmpty();
        assertThat(config.getImageChannelKey()).isEmpty();
        assertThat(config.getImageSize()).isEqualTo(ToutiaoImageHelper.DEFAULT_SIZE);
        assertThat(config.getImageStylePrompt()).isEqualTo(ToutiaoImageHelper.DEFAULT_STYLE_PROMPT);
    }

    @Test
    void imageCountShouldNeverGoBelowThree() {
        var node = MAPPER.createObjectNode();
        node.put("imageCount", 0);
        assertThat(ToutiaoConfigHelper.parse(node).getImageCount())
            .isEqualTo(ToutiaoImageHelper.MIN_IMAGES);

        node.put("imageCount", 99);
        assertThat(ToutiaoConfigHelper.parse(node).getImageCount())
            .isEqualTo(ToutiaoImageHelper.MAX_IMAGES);
    }

    @Test
    void imageFieldsShouldBeTrimmedAndKept() {
        var node = MAPPER.createObjectNode();
        node.put("imageModel", "  wanx2.1-t2i-turbo  ");
        node.put("imageChannelKey", " primary ");
        node.put("imageSize", " 1280x720 ");
        var config = ToutiaoConfigHelper.parse(node);

        assertThat(config.getImageModel()).isEqualTo("wanx2.1-t2i-turbo");
        assertThat(config.getImageChannelKey()).isEqualTo("primary");
        assertThat(config.getImageSize()).isEqualTo("1280x720");
    }
}
