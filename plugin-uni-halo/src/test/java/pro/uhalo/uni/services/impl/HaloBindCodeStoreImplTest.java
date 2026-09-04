package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaloBindCodeStoreImplTest {

    @Test
    void buildKeysShouldNormalizeOpenidAndAccount() {
        assertThat(HaloBindCodeStoreImpl.buildCodeKey("OpenId", "DemoUser"))
            .isEqualTo("bind:code:openid:demouser");
        assertThat(HaloBindCodeStoreImpl.buildRateKey("OpenId", "DemoUser"))
            .isEqualTo("bind:rate:openid:demouser");
    }
}
