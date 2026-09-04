package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.PointsConfigDTO;

import static org.assertj.core.api.Assertions.assertThat;

class PointsConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsShouldMatchFrontendStructure() {
        var defaults = PointsConfigHelper.defaults();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.isShowInProfile()).isTrue();
        assertThat(defaults.getMinBrowseDuration()).isEqualTo(5);
        assertThat(defaults.getRules().getBrowse().getPoints()).isEqualTo(1);
        assertThat(defaults.getRules().getBrowse().getDailyMaxCount()).isEqualTo(10);
        assertThat(defaults.getRules().getComment().getPoints()).isEqualTo(5);
        assertThat(defaults.getRules().getUpvote().getPoints()).isEqualTo(2);
        assertThat(defaults.getRules().getSignIn().getPoints()).isEqualTo(10);
        assertThat(defaults.getRules().getSignIn().getConsecutiveBonus()).hasSize(2);
        assertThat(defaults.getRules().getSignIn().getConsecutiveBonus().get(0).getDays()).isEqualTo(3);
        assertThat(defaults.getRules().getSignIn().getConsecutiveBonus().get(1).getBonus()).isEqualTo(20);
    }

    @Test
    void parseShouldReadNestedRules() throws Exception {
        var json = """
            {
              "enabled": true,
              "showInProfile": false,
              "minBrowseDuration": 8,
              "rules": {
                "browse": { "points": 2, "dailyMaxCount": 5, "dailyMaxPoints": 8 },
                "signIn": {
                  "points": 12,
                  "consecutiveBonus": [ { "days": 5, "bonus": 10 } ]
                }
              }
            }
            """;
        var node = MAPPER.readTree(json);
        PointsConfigDTO config = PointsConfigHelper.parse(node);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isShowInProfile()).isFalse();
        assertThat(config.getMinBrowseDuration()).isEqualTo(8);
        assertThat(config.getRules().getBrowse().getDailyMaxPoints()).isEqualTo(8);
        assertThat(config.getRules().getSignIn().getPoints()).isEqualTo(12);
        assertThat(config.getRules().getSignIn().getConsecutiveBonus()).singleElement()
            .satisfies(bonus -> {
                assertThat(bonus.getDays()).isEqualTo(5);
                assertThat(bonus.getBonus()).isEqualTo(10);
            });
    }
}
