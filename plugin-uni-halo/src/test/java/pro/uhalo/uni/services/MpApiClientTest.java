package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.utils.MpErrors;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传请求的构造与传输层错误翻译。
 *
 * <p>线上踩过：微信前置网关在请求<b>没有 Content-Length</b>（WebFlux 的 multipart 默认 chunked）
 * 时直接回 {@code 412 Precondition Failed}，且响应体是空的——日志里只有一句英文，
 * 站长无从下手。所以这里既钉住「手写 multipart 一定带长度与 part 头」，
 * 也钉住「412 能翻译成人话、并触发压小重试」。</p>
 */
class MpApiClientTest {

    /**
     * 接口地址拼接：线上踩过一次「永久素材地址少了 ?，type=image 没传出去」，
     * 微信回 40066 invalid url，素材上传从第一版起就没成功过——这里把每个地址都钉一遍。
     */
    @Test
    void everyApiUrlShouldBeWellFormedAfterAppendingToken() {
        for (var url : MpApiClient.allApiUrls()) {
            assertThat(url).startsWith("https://api.weixin.qq.com/cgi-bin/");
            // 地址本身最多带一个 query，且不能以 ? / & 结尾
            assertThat(url.chars().filter(ch -> ch == '?').count()).isLessThanOrEqualTo(1);
            assertThat(url).doesNotEndWith("?").doesNotEndWith("&");

            var withToken = MpApiClient.appendToken(url, "TOKEN");
            // 拼完之后只允许有一个 ?（多个 ? 会让后面的参数全部失效）
            assertThat(withToken.chars().filter(ch -> ch == '?').count())
                .as("拼 token 后出现多个问号：%s", withToken)
                .isEqualTo(1);
            assertThat(withToken).endsWith("access_token=TOKEN");
        }
    }

    @Test
    void permanentMaterialUrlShouldKeepItsTypeParameter() {
        var withToken = MpApiClient.appendToken(MpApiClient.ADD_MATERIAL_URL, "T");

        // 正确形式：.../add_material?type=image&access_token=T
        assertThat(withToken).contains("?type=image&access_token=T");
        assertThat(withToken).doesNotContain("add_material&type=image");
    }

    @Test
    void urlsWithoutQueryShouldUseQuestionMark() {
        assertThat(MpApiClient.appendToken(MpApiClient.UPLOAD_CONTENT_IMAGE_URL, "T"))
            .isEqualTo(MpApiClient.UPLOAD_CONTENT_IMAGE_URL + "?access_token=T");
    }

    @Test
    void multipartBodyShouldCarryFilenameContentTypeAndBoundary() {        var boundary = "----UniHaloMpTEST";
        var data = new byte[] {1, 2, 3, 4, 5};

        var body = MpApiClient.multipartBody(boundary, "media", "a.jpg", "image/jpeg", data);
        var text = new String(body, StandardCharsets.ISO_8859_1);

        assertThat(text).startsWith("--" + boundary + "\r\n");
        assertThat(text).contains("Content-Disposition: form-data; name=\"media\"; filename=\"a.jpg\"");
        assertThat(text).contains("Content-Type: image/jpeg");
        assertThat(text).contains("\r\n\r\n");
        assertThat(text).endsWith("\r\n--" + boundary + "--\r\n");
        // 字节数 = 头部 + 原始数据 + 尾部（Content-Length 必须与它一致）
        assertThat(body.length).isEqualTo(text.length());
        assertThat(body.length).isGreaterThan(data.length);
    }

    @Test
    void transport412ShouldBecomeActionableError() {
        var exception = WebClientResponseException.create(412, "Precondition Failed",
            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        var error = MpApiClient.transportError(exception);

        assertThat(error.errcode()).isEqualTo(-412);
        assertThat(error.suggestion()).contains("412").contains("Content-Length");
        assertThat(MpErrors.worthRetrySmaller(error)).isTrue();
    }

    @Test
    void transportErrorShouldPreferBusinessErrcodeWhenBodyIsJson() {
        var body = "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}".getBytes(StandardCharsets.UTF_8);
        var exception = WebClientResponseException.create(500, "Internal Server Error",
            HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);

        var error = MpApiClient.transportError(exception);

        assertThat(error.errcode()).isEqualTo(40001);
        assertThat(error.tokenInvalid()).isTrue();
    }

    @Test
    void oversizeBusinessCodesShouldRetrySmaller() {
        assertThat(MpErrors.worthRetrySmaller(MpErrors.of(-413, ""))).isTrue();
        assertThat(MpErrors.worthRetrySmaller(MpErrors.of(40006, "invalid file size"))).isTrue();
        assertThat(MpErrors.worthRetrySmaller(MpErrors.of(40009, "invalid image size"))).isTrue();
        // 权限/白名单问题压小也没用，不该白白重传
        assertThat(MpErrors.worthRetrySmaller(MpErrors.of(40164, "invalid ip"))).isFalse();
        assertThat(MpErrors.worthRetrySmaller(MpErrors.of(48001, "api unauthorized"))).isFalse();
    }

    @Test
    void serverErrorsShouldBeMarkedTransient() {
        var exception = WebClientResponseException.create(502, "Bad Gateway",
            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        var error = MpApiClient.transportError(exception);

        assertThat(error.errcode()).isEqualTo(-502);
        assertThat(error.suggestion()).contains("稍后重试");
    }
}
