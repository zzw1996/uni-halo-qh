package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.MpConfigDTO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公众号配置解析：默认值、边界收敛与「方向清单留空 = 内置三项」的口径。
 */
class MpConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptyConfigShouldFallBackToSafeDefaults() {
        var config = MpConfigHelper.parse(MAPPER.createObjectNode());
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getDeliveryMode()).isEqualTo(MpConfigHelper.DELIVERY_DRAFT);
        // 定时生成默认关闭：只有点「立即生成」才会生成（历史行为是「全天补跑」，很吓人）
        assertThat(config.isScheduleEnabled()).isFalse();
        // 方向保底默认开启：某方向素材不足时用备选方向补位
        assertThat(config.isDirectionFallbackEnabled()).isTrue();
        assertThat(config.getScheduleTimes()).isEqualTo("08:00");
        assertThat(config.getPerDirectionTopN()).isEqualTo(3);
        assertThat(config.getDigestWords()).isEqualTo(MpConfigHelper.DEFAULT_DIGEST_WORDS);
        assertThat(config.isUploadImages()).isTrue();
        assertThat(config.isPostToSite()).isFalse();
    }

    @Test
    void digestWordsShouldBeClampedToOfficialLimit() {
        var node = MAPPER.createObjectNode();
        node.put("digestWords", 500);
        assertThat(MpConfigHelper.parse(node).getDigestWords())
            .isEqualTo(MpConfigHelper.MAX_DIGEST_WORDS);
        node.put("digestWords", 1);
        assertThat(MpConfigHelper.parse(node).getDigestWords())
            .isEqualTo(MpConfigHelper.MIN_DIGEST_WORDS);
    }

    @Test
    void emptyDirectionsShouldFallBackToBuiltInThree() {
        var config = MpConfigHelper.parse(MAPPER.createObjectNode());
        var directions = MpConfigHelper.publishingDirections(config);
        assertThat(directions).extracting("key")
            .containsExactly("ai-dev", "security", "vibe-coding");
        // 兜底分类自动追加，但不参与成文
        assertThat(MpConfigHelper.effectiveDirections(config)).extracting("key")
            .contains(MpConfigHelper.FALLBACK_KEY);
    }

    @Test
    void configuredDirectionsShouldWinAndFallbackIsAppendedOnce() {
        var json = """
            {"directions": [
              {"key": "ai-dev", "label": "AI 发展", "emoji": "🤖", "desc": "AI 相关"},
              {"key": "other", "label": "其他", "emoji": "📝", "desc": "兜底"}
            ]}
            """;
        MpConfigDTO config;
        try {
            config = MpConfigHelper.parse(MAPPER.readTree(json));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        assertThat(MpConfigHelper.publishingDirections(config)).extracting("key")
            .containsExactly("ai-dev");
        assertThat(MpConfigHelper.effectiveDirections(config)).extracting("key")
            .containsExactly("ai-dev", "other");
    }

    @Test
    void directionKeysShouldBeSanitized() {
        var json = """
            {"directions": [
              {"key": "Vibe Coding!", "label": "Vibe Coding"},
              {"key": "security", "label": "AI 安全"}
            ]}
            """;
        MpConfigDTO config;
        try {
            config = MpConfigHelper.parse(MAPPER.readTree(json));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        // 非法 key 被消毒丢弃，合法的那条保留
        assertThat(MpConfigHelper.publishingDirections(config)).extracting("key")
            .containsExactly("security");
    }

    @Test
    void deliveryModeShouldNormalizeAliases() {
        var node = MAPPER.createObjectNode();
        node.put("deliveryMode", "freepublish");
        assertThat(MpConfigHelper.parse(node).getDeliveryMode())
            .isEqualTo(MpConfigHelper.DELIVERY_PUBLISH);
        node.put("deliveryMode", "sendall");
        assertThat(MpConfigHelper.parse(node).getDeliveryMode())
            .isEqualTo(MpConfigHelper.DELIVERY_MASS);
        node.put("deliveryMode", "乱填");
        assertThat(MpConfigHelper.parse(node).getDeliveryMode())
            .isEqualTo(MpConfigHelper.DELIVERY_DRAFT);
    }

    @Test
    void scheduleTimesShouldFallBackToEight() {
        assertThat(MpConfigHelper.parseScheduleTimes(""))
            .containsExactly(java.time.LocalTime.of(8, 0));
        assertThat(MpConfigHelper.parseScheduleTimes("乱填,09:30"))
            .containsExactly(java.time.LocalTime.of(9, 30));
    }

    @Test
    void errorsShouldExtractIpFromWhitelistFailure() {
        var error = MpErrors.of(40164,
            "invalid ip 8.149.132.108 ipv6 ::ffff:8.149.132.108, not in whitelist rid: xyz");
        assertThat(error.ip()).isEqualTo("8.149.132.108");
        assertThat(error.configProblem()).isTrue();
        assertThat(error.suggestion()).contains("IP 白名单");
    }

    @Test
    void unknownErrcodeShouldStillBeVisibleInTheMessage() {
        // 线上踩过：默认分支只说「未知错误码」，把 errcode/errmsg 吞掉了，等于没有线索
        var error = MpErrors.of(99999, "something odd happened");

        assertThat(error.suggestion()).contains("99999").contains("something odd happened");
    }

    @Test
    void materialForbiddenShouldPointToTheCopyPath() {
        var error = MpErrors.of(48002, "api forbidden");

        assertThat(error.permissionDenied()).isTrue();
        assertThat(error.suggestion()).contains("永久素材").contains("复制正文");
    }

    @Test
    void wechatSystemBusyShouldNotBeConfusedWithTransport() {
        var error = MpErrors.of(-1, "system error");

        assertThat(error.suggestion()).contains("系统繁忙");
        assertThat(MpErrors.worthRetrySmaller(error)).isFalse();
    }

    @Test
    void pluginCodesShouldKeepTheirMessage() {
        var error = MpErrors.of(MpErrors.PLUGIN_CODE, "请求超时（ReadTimeoutException）");

        assertThat(error.suggestion()).contains("请求超时");
        assertThat(error.errcode()).isEqualTo(MpErrors.PLUGIN_CODE);
    }

    @Test
    void errorsShouldClassifyCommonCodes() {
        assertThat(MpErrors.of(40001, "invalid credential").tokenInvalid()).isTrue();
        assertThat(MpErrors.of(48001, "api unauthorized").permissionDenied()).isTrue();
        assertThat(MpErrors.of(88000, "without comment privilege").configProblem()).isTrue();
        assertThat(MpErrors.of(0, "").errcode()).isZero();
    }
}
