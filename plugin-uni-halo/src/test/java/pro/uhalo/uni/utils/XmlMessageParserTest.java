package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlMessageParserTest {

    private static final String DELIVER_XML =
        "<xml><ToUserName><![CDATA[gh_abc]]></ToUserName>"
            + "<FromUserName><![CDATA[gh_openid]]></FromUserName>"
            + "<CreateTime>1756972800</CreateTime>"
            + "<MsgType><![CDATA[event]]></MsgType>"
            + "<Event><![CDATA[xpay_goods_deliver_notify]]></Event>"
            + "<OpenId><![CDATA[oAbCdEf123]]></OpenId>"
            + "<OutTradeNo>MP17569728000001234</OutTradeNo>"
            + "<WxOrderId>2026000100010001</WxOrderId>"
            + "<Quantity>1</Quantity><ActualPrice>10</ActualPrice>"
            + "<Attach>MP17569728000001234</Attach>"
            + "<PaidTime>1756972801</PaidTime></xml>";

    @Test
    void shouldExtractCDataField() {
        assertThat(XmlMessageParser.value(DELIVER_XML, "Event")).isEqualTo("xpay_goods_deliver_notify");
        assertThat(XmlMessageParser.value(DELIVER_XML, "OpenId")).isEqualTo("oAbCdEf123");
    }

    @Test
    void shouldExtractPlainTextFieldCaseInsensitive() {
        assertThat(XmlMessageParser.value(DELIVER_XML, "outtradeno")).isEqualTo("MP17569728000001234");
        assertThat(XmlMessageParser.value(DELIVER_XML, "WxOrderId")).isEqualTo("2026000100010001");
        assertThat(XmlMessageParser.value(DELIVER_XML, "wx_order_id")).isNull();
    }

    @Test
    void valueFirstOfShouldFallbackAcrossCandidates() {
        assertThat(XmlMessageParser.valueFirstOf(DELIVER_XML, "WxOrderId", "wx_order_id"))
            .isEqualTo("2026000100010001");
        assertThat(XmlMessageParser.valueFirstOf(DELIVER_XML, "MissingA", "MissingB")).isNull();
    }

    @Test
    void shouldExtractNestedWechatPayInfoFields() {
        // 官方推送中平台单号位于 WeChatPayInfo.MchOrderNo（嵌套结构）
        var nested = "<xml><OpenId><![CDATA[oAbCdEf123]]></OpenId>"
            + "<OutTradeNo>MP17569728000001234</OutTradeNo>"
            + "<WeChatPayInfo><MchOrderNo>2026000100010001</MchOrderNo>"
            + "<TransactionId>4200001001202609</TransactionId></WeChatPayInfo>"
            + "<GoodsInfo><ProductId>prod-001</ProductId><Quantity>1</Quantity></GoodsInfo></xml>";
        assertThat(XmlMessageParser.value(nested, "MchOrderNo")).isEqualTo("2026000100010001");
        assertThat(XmlMessageParser.value(nested, "TransactionId")).isEqualTo("4200001001202609");
        assertThat(XmlMessageParser.value(nested, "ProductId")).isEqualTo("prod-001");
        assertThat(XmlMessageParser.value(nested, "Quantity")).isEqualTo("1");
    }

    @Test
    void missingFieldShouldReturnNull() {
        assertThat(XmlMessageParser.value(DELIVER_XML, "NotThere")).isNull();
        assertThat(XmlMessageParser.value(null, "Event")).isNull();
    }
}
