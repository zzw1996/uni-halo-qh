package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.dto.InviteQRCodeDataDTO;
import pro.uhalo.uni.scheme.QRCodeInfo;
import pro.uhalo.uni.services.QRCodeInfoService;
import pro.uhalo.uni.services.UniHaloService;
import pro.uhalo.uni.utils.WxAccessTokenProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务实现
 *
 * @author 小莫唐尼
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UniHaloServiceImpl implements UniHaloService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final WebClient WEB_CLIENT = WebClient.builder().build();
    private static final SecureRandom SCENE_KEY_RANDOM = new SecureRandom();
    private static final String SCENE_KEY_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final QRCodeInfoService qrCodeInfoService;
    private final ReactiveSettingFetcher settingFetcher;
    private final WxAccessTokenProvider wxAccessTokenProvider;
    private final AttachmentService attachmentService;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ConcurrentHashMap<String, Mono<String>> qrCodeGenerating = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Mono<InviteQRCodeDataDTO>> inviteQRCodeGenerating = new ConcurrentHashMap<>();

    private static final String INVITE_SCENE_PREFIX = "inv:";

    /** 虚拟支付配置分组名 */
    private static final String PAY_CONFIG_GROUP = "payConfig";

    /** 仅供服务端使用、禁止下发到小程序的支付敏感字段 */
    private static final java.util.Set<String> PAY_SECRET_FIELDS =
        java.util.Set.of("appKey", "offerId");

    /** 基本配置分组名（内含个人令牌） */
    private static final String BASIC_CONFIG_GROUP = "basicConfig";

    /** 仅供服务端使用、禁止下发到小程序的个人令牌字段 */
    private static final java.util.Set<String> BASIC_SECRET_FIELDS =
        java.util.Set.of("personalToken");

    /** 底部导航配置分组名 */
    private static final String TABBAR_CONFIG_GROUP = "tabbarConfig";

    /** AI 日报配置分组名（内含大模型 Key） */
    private static final String AI_DIGEST_CONFIG_GROUP = "aiDigestConfig";

    /**
     * AI 日报分组里禁止下发的密钥字段。
     *
     * <p>{@code aiProviders} 是清单，逐条的 Key 在 {@link #stripAiDigestSecrets} 里单独剥掉；
     * 剩下三个是清单为空时生效的兼容通道密钥。</p>
     */
    private static final java.util.Set<String> AI_DIGEST_SECRET_FIELDS =
        java.util.Set.of("aiApiKey", "fallbackApiKey");

    /**
     * 底部导航最少/最多显示项。
     *
     * <p>微信官方的限制只针对原生 {@code tabBar.list}：2~5 项。这里是产品口径——
     * 下限 3（不至于空得不像底栏）、上限 5（与原生一致），改这里即可整体调整。</p>
     */
    private static final int TABBAR_MIN_ITEMS = 3;
    private static final int TABBAR_MAX_ITEMS = 5;

    /**
     * 底部导航的固定项：不提供开关，也不会被归一化裁掉（首页与「我的」关掉后底栏会失去锚点）。
     */
    private static final TabbarPreset TABBAR_LOCKED_HEAD = new TabbarPreset("home", null, true,
        "首页", "select_home", "/pages/tabbar/home/home", true, null);

    private static final TabbarPreset TABBAR_LOCKED_TAIL = new TabbarPreset("about", null, true,
        "我的", "select_mine", "/pages/tabbar/about/about", true, null);

    /**
     * 可选项。<b>顺序即规则</b>：不足 4 项时按此顺序补齐，超过 5 项时按逆序丢弃。
     *
     * <p>{@code nativeTab=true} 表示对应页面在 pages.json 的 tabBar.list 里，能走 switchTab（保留页面状态）。
     * 微信原生 tabBar 最多只能声明 5 项，而候选有 7 项，所以图库/小店以「视图」形式挂在瞬间页里：
     * 它们的 path 指向宿主页、{@code mode} 指明视图名，底栏点它们 = switchTab 宿主页 + 切视图，
     * 表现与原生 tab 一致（无返回箭头、不重载）。</p>
     */
    private static final java.util.List<TabbarPreset> TABBAR_OPTIONAL = java.util.List.of(
        // 页面已从「分类」重做成「发现」（分类发现 + 搜索/排序 + 订阅热度榜）。
        // key 保持 category：改 key 会让站长的 showCategory 开关与用户本地偏好全部失配
        new TabbarPreset("category", "showCategory", false, "发现", "select_category",
            "/pages/tabbar/discover/discover", true, null),
        new TabbarPreset("moments", "showMoments", false, "瞬间", "select_gallery",
            "/pages/tabbar/moments/moments", true, "moments"),
        new TabbarPreset("rank", "showRank", false, "榜单", "select_rank",
            "/pages/tabbar/rank/rank", true, null),
        // 图库 / 小店挂在瞬间页上（同一个原生页，靠 mode 区分视图）
        new TabbarPreset("gallery", "showGallery", false, "图库", "select_picture",
            "/pages/tabbar/moments/moments", true, "gallery"),
        new TabbarPreset("store", "showStore", false, "小店", "select_shop",
            "/pages/tabbar/moments/moments", true, "store"));

    public static String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    public static String GET_WXA_CODE_URL = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=";

    @Override
    public Mono<Map<String, JsonNode>> getAppConfigs() {
        // 全量配置接口为匿名可访问，且小程序会把响应整体写入本地存储，
        // 因此必须在此剥离仅供服务端使用的密钥，避免下发到客户端：
        // - 支付密钥（paySig 签名用）
        // - 个人令牌（等同于管理员权限：拿到它就能以管理员身份调 Halo 任何接口）
        // - 大模型 API Key（能直接消费站长的 AI 额度）
        return this.settingFetcher.getValues()
            .map(configs -> {
                var sanitized = new HashMap<String, JsonNode>(configs);
                var payConfig = sanitized.get(PAY_CONFIG_GROUP);
                if (payConfig != null) {
                    sanitized.put(PAY_CONFIG_GROUP, stripPaySecrets(payConfig));
                }
                var basicConfig = sanitized.get(BASIC_CONFIG_GROUP);
                if (basicConfig != null) {
                    sanitized.put(BASIC_CONFIG_GROUP, stripBasicSecrets(basicConfig));
                }
                var tabbarConfig = sanitized.get(TABBAR_CONFIG_GROUP);
                if (tabbarConfig != null) {
                    sanitized.put(TABBAR_CONFIG_GROUP, normalizeTabbarConfig(tabbarConfig));
                }
                var aiDigestConfig = sanitized.get(AI_DIGEST_CONFIG_GROUP);
                if (aiDigestConfig != null) {
                    sanitized.put(AI_DIGEST_CONFIG_GROUP, stripAiDigestSecrets(aiDigestConfig));
                }
                return sanitized;
            });
    }

    @Override
    public Mono<JsonNode> getAppConfigsByGroupName(String groupName) {
        // 按分组查询同样匿名可访问，敏感分组需一并脱敏
        return this.settingFetcher.get(groupName)
            .map(config -> {
                if (PAY_CONFIG_GROUP.equals(groupName)) {
                    return stripPaySecrets(config);
                }
                if (BASIC_CONFIG_GROUP.equals(groupName)) {
                    return stripBasicSecrets(config);
                }
                if (TABBAR_CONFIG_GROUP.equals(groupName)) {
                    return normalizeTabbarConfig(config);
                }
                if (AI_DIGEST_CONFIG_GROUP.equals(groupName)) {
                    return stripAiDigestSecrets(config);
                }
                return config;
            });
    }

    /**
     * 移除 AI 日报分组里的密钥：{@code aiApiKey}、{@code fallbackApiKey}，
     * 以及通道清单 {@code aiProviders} 里每一条的 {@code apiKey}。
     *
     * <p>大模型 Key 能直接消费站长的 AI 额度，而这里是匿名接口。</p>
     */
    private static JsonNode stripAiDigestSecrets(JsonNode config) {
        if (config == null || !(config instanceof ObjectNode node)) {
            return config;
        }
        var copy = node.deepCopy();
        AI_DIGEST_SECRET_FIELDS.forEach(copy::remove);
        var providers = copy.get("aiProviders");
        if (providers != null && providers.isArray()) {
            for (var item : providers) {
                if (item instanceof ObjectNode entry) {
                    entry.remove("apiKey");
                }
            }
        }
        return copy;
    }

    /**
     * 移除虚拟支付密钥字段。
     *
     * <p>appKey 用于服务端计算 paySig 签名，一旦泄露任何人可伪造签名下单；
     * offerId 同样只在服务端构造 signData 时使用。小程序端只需要 enabled 与 goods
     * 两项即可完成「是否展示入口」与「商品列表渲染」。</p>
     */
    private static JsonNode stripPaySecrets(JsonNode config) {
        if (config == null || !(config instanceof ObjectNode node)) {
            return config;
        }
        var copy = node.deepCopy();
        PAY_SECRET_FIELDS.forEach(copy::remove);
        return copy;
    }

    /**
     * 移除个人令牌（{@code basicConfig.tokenConfig.personalToken}）。
     *
     * <p><b>为什么必须删</b>：个人令牌等同管理员权限，而这又是匿名接口、响应还会被小程序
     * 整包写进本地存储——之前「瞬间发布」就是靠客户端拿着它直连 Halo 实现的，
     * 等于把管理员身份发给了每个用户。现在瞬间发布/删除/配图上传都改由服务端用令牌代办，
     * 客户端不再需要它。</p>
     */
    private static JsonNode stripBasicSecrets(JsonNode config) {
        if (config == null || !(config instanceof ObjectNode node)) {
            return config;
        }
        var copy = node.deepCopy();
        var tokenConfig = copy.get("tokenConfig");
        if (tokenConfig instanceof ObjectNode tokenNode) {
            BASIC_SECRET_FIELDS.forEach(tokenNode::remove);
        }
        return copy;
    }

    /**
     * 归一化底部导航配置：把「哪些项要显示」的一组开关整理成小程序可直接渲染的 {@code items[]}。
     *
     * <p><b>为什么必须在服务端做</b>：Halo 的插件设置由 core 直接写 ConfigMap，插件拿不到保存钩子，
     * 表单里也做不了跨字段校验；而「最少 4 项、最多 5 项」是微信原生底栏的硬约束
     * （原生 list 上限 5 项，少于 4 项底栏会退化成不像底栏的样子），任何情况下都必须成立，
     * 所以只能在下发配置时兜住。补齐/裁剪只影响下发结果，不修改站长保存的配置本身。</p>
     */
    private static JsonNode normalizeTabbarConfig(JsonNode config) {
        if (config == null || !(config instanceof ObjectNode node)) {
            return config;
        }
        var result = node.deepCopy();
        if (!result.path("enabled").asBoolean(false)) {
            // 没开启就原样返回：小程序端只看 enabled，不会接管底栏
            return result;
        }

        // 1. 按开关收集可选项
        var picked = new ArrayList<TabbarPreset>();
        for (var preset : TABBAR_OPTIONAL) {
            if (result.path(preset.switchField()).asBoolean(preset.defaultOn())) {
                picked.add(preset);
            }
        }
        // 固定项占 2 席，可选项的可选数量区间就是这个区间
        int minOptional = TABBAR_MIN_ITEMS - 2;
        int maxOptional = TABBAR_MAX_ITEMS - 2;
        // 2. 超过上限：按预设逆序裁掉（小店 → 图库 → 榜单 → 瞬间 → 分类）
        for (int i = TABBAR_OPTIONAL.size() - 1; i >= 0 && picked.size() > maxOptional; i--) {
            picked.remove(TABBAR_OPTIONAL.get(i));
        }
        // 3. 不足下限：按预设顺序补齐（补齐项等于默认显示；只影响下发结果，不改站长保存的配置）
        for (var preset : TABBAR_OPTIONAL) {
            if (picked.size() >= minOptional) {
                break;
            }
            if (!picked.contains(preset)) {
                picked.add(preset);
            }
        }

        // 4. 组装最终清单：首页 + 可选项 + 我的。
        //    这里按 TABBAR_OPTIONAL 顺序遍历输出，而不是直接用 picked 的顺序——
        //    第 3 步补进来的项是追加在末尾的，直接用会把顺序打乱（例如补进来的「分类」跑到「小店」后面）。
        var items = JsonNodeFactory.instance.arrayNode();
        items.add(toTabbarItemNode(TABBAR_LOCKED_HEAD));
        for (var preset : TABBAR_OPTIONAL) {
            if (picked.contains(preset)) {
                items.add(toTabbarItemNode(preset));
            }
        }
        items.add(toTabbarItemNode(TABBAR_LOCKED_TAIL));
        result.set("items", items);

        // 5. 候选清单：小程序端「我的 → 系统设置」里让用户自己勾选时要用。
        //    必须把全部 7 项都下发（含站长关掉的），否则用户没有可选的东西；
        //    locked 标记告诉前端哪些项不许取消（首页/我的）。
        //    adminOn 是站长那份开关的原始值，用于「恢复站长默认」。
        var candidates = JsonNodeFactory.instance.arrayNode();
        candidates.add(toTabbarCandidateNode(TABBAR_LOCKED_HEAD, true));
        for (var preset : TABBAR_OPTIONAL) {
            candidates.add(toTabbarCandidateNode(preset,
                result.path(preset.switchField()).asBoolean(preset.defaultOn())));
        }
        candidates.add(toTabbarCandidateNode(TABBAR_LOCKED_TAIL, true));
        result.set("candidates", candidates);
        return result;
    }

    private static ObjectNode toTabbarItemNode(TabbarPreset preset) {
        var item = JsonNodeFactory.instance.objectNode();
        item.put("key", preset.key());
        item.put("label", preset.label());
        item.put("icon", "/static/tabbar/" + preset.icon() + ".png");
        item.put("activeIcon", "/static/tabbar/" + preset.icon() + "_active.png");
        item.put("path", preset.pagePath());
        item.put("nativeTab", preset.nativeTab());
        // 宿主页的视图名：小程序端据此判断「切视图」而不是「换页面」
        if (preset.mode() != null) {
            item.put("mode", preset.mode());
        }
        return item;
    }

    /**
     * 候选项节点：items[] 的超集（多了 locked 与 adminOn），供小程序端做用户级偏好勾选。
     *
     * @param locked  固定项（首页/我的）：前端不给开关，服务端归一化也不会裁掉
     * @param adminOn 站长开关的原始值
     */
    private static ObjectNode toTabbarCandidateNode(TabbarPreset preset, boolean adminOn) {
        var node = toTabbarItemNode(preset);
        node.put("locked", preset.switchField() == null);
        node.put("adminOn", adminOn);
        return node;
    }

    /**
     * 底部导航候选项。
     *
     * @param key        小程序端用来标记当前项（页面里写死的 current 值）
     * @param switchField 配置里控制它显示与否的开关字段名；固定项为 null
     * @param defaultOn  开关缺失时的默认值（小店默认关，其余默认开）
     * @param label      底栏文案
     * @param icon       图标文件名前缀，实际路径为 /static/tabbar/{icon}.png 与 {icon}_active.png
     * @param pagePath   页面路径
     * @param nativeTab  是否为 pages.json 里声明的原生 tabBar 页（决定用 switchTab 还是 navigateTo）
     * @param mode       多视图宿主页里的视图名；普通页面为 null。
     *                   微信原生 tab 页上限 5 项、候选有 7 项，于是图库/小店作为「视图」
     *                   挂在瞬间页（原生页）里，底栏点它们等于 switchTab 到宿主页再切视图。
     */
    private record TabbarPreset(String key, String switchField, boolean defaultOn, String label,
                                String icon, String pagePath, boolean nativeTab, String mode) {
    }

    @Override
    public Mono<String> resolveQRCodeImageUrl(String postId) {
        Mono<String> generating = qrCodeGenerating.computeIfAbsent(postId, id ->
            doResolveQRCodeImageUrl(postId)
                .cache()
                .doFinally(signal -> qrCodeGenerating.remove(id))
        );
        return generating;
    }

    private Mono<String> doResolveQRCodeImageUrl(String postId) {
        return qrCodeInfoService.fetchByPostId(postId)
            .flatMap(existing -> {
                if (!StringUtils.hasText(existing.getImageUrl())) {
                    return generateAndPersistQRCode(postId, existing);
                }
                return Mono.defer(() -> Mono.just(
                    externalLinkProcessor.processLink(existing.getImageUrl())));
            })
            .switchIfEmpty(Mono.defer(() -> {
                QRCodeInfo qrCodeInfo = new QRCodeInfo();
                qrCodeInfo.setPostId(postId);
                return qrCodeInfoService.save(qrCodeInfo)
                    .switchIfEmpty(Mono.error(new IllegalStateException("二维码记录创建失败，请稍后重试")))
                    .flatMap(info -> generateAndPersistQRCode(postId, info));
            }));
    }

    @Override
    public Mono<InviteQRCodeDataDTO> resolveInviteQRCodeData(String inviteCode) {
        if (!StringUtils.hasText(inviteCode)) {
            return Mono.error(new IllegalStateException("邀请码不能为空"));
        }
        var normalized = inviteCode.trim().toUpperCase();
        Mono<InviteQRCodeDataDTO> generating = inviteQRCodeGenerating.computeIfAbsent(normalized, code ->
            generateInviteQRCodeData(code)
                .cache()
                .doFinally(signal -> inviteQRCodeGenerating.remove(code))
        );
        return generating;
    }

    private Mono<InviteQRCodeDataDTO> generateInviteQRCodeData(String inviteCode) {
        var sceneKey = INVITE_SCENE_PREFIX + inviteCode;
        return ensureAccessToken()
            .flatMap(token -> requestWxacode(token, sceneKey))
            .flatMap(bytes -> {
                var base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                return getMediaUrl("invite_" + inviteCode, bytes)
                    .map(url -> externalLinkProcessor.processLink(url))
                    .map(url -> InviteQRCodeDataDTO.builder()
                        .inviteCode(inviteCode)
                        .imageUrl(url)
                        .base64(base64)
                        .build())
                    .switchIfEmpty(Mono.defer(() -> Mono.just(InviteQRCodeDataDTO.builder()
                        .inviteCode(inviteCode)
                        .base64(base64)
                        .build())))
                    .onErrorResume(ex -> {
                        log.warn("invite QR upload failed, code={}: {}", inviteCode, ex.getMessage());
                        return Mono.just(InviteQRCodeDataDTO.builder()
                            .inviteCode(inviteCode)
                            .base64(base64)
                            .build());
                    });
            });
    }

    private Mono<String> generateAndPersistQRCode(String postId, QRCodeInfo info) {
        return ensureSceneKey(info, postId)
            .flatMap(readyInfo -> ensureAccessToken()
                .flatMap(token -> requestWxacode(token, readyInfo.getKey()))
                .flatMap(bytes -> getMediaUrl(postId, bytes))
                .flatMap(url -> {
                    readyInfo.setImageUrl(url);
                    return qrCodeInfoService.update(readyInfo)
                        .onErrorResume(e -> qrCodeInfoService.fetchByPostId(postId))
                        .map(updatedInfo -> {
                            String urlToUse = updatedInfo != null && StringUtils.hasText(updatedInfo.getImageUrl())
                                ? updatedInfo.getImageUrl()
                                : url;
                            return externalLinkProcessor.processLink(urlToUse);
                        });
                }));
    }

    @Override
    public Mono<String> uploadImageToAttachments(byte[] bytes, String safeName) {
        return uploadMedia(safeName, bytes)
            .flatMap(this::getPermalink)
            .onErrorResume(ex -> ex instanceof IllegalStateException
                ? Mono.error(ex)
                : Mono.error(new IllegalStateException("图片上传失败：" + ex.getMessage(), ex)))
            .map(url -> externalLinkProcessor.processLink(url));
    }

    private Mono<QRCodeInfo> ensureSceneKey(QRCodeInfo info, String postId) {
        if (StringUtils.hasText(info.getKey())) {
            return Mono.just(info);
        }
        String key = info.getMetadata() != null && StringUtils.hasText(info.getMetadata().getName())
            ? info.getMetadata().getName()
            : generateSceneKey();
        info.setKey(key);
        if (info.getMetadata() == null) {
            Metadata metadata = new Metadata();
            metadata.setName(key);
            metadata.setCreationTimestamp(Instant.now());
            info.setMetadata(metadata);
        } else if (!StringUtils.hasText(info.getMetadata().getName())) {
            info.getMetadata().setName(key);
        }
        return qrCodeInfoService.update(info)
            .onErrorResume(e -> Mono.just(info));
    }

    private String generateSceneKey() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(SCENE_KEY_CHARS.charAt(SCENE_KEY_RANDOM.nextInt(SCENE_KEY_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 取 access_token。
     *
     * <p>缓存与换取都交给 {@link WxAccessTokenProvider}（全插件共用一份，避免多处缓存互相挤掉）；
     * 这里的错误类型保持 {@code IllegalStateException} 不变——小程序码接口对外的 500 语义
     * 由它决定，收口 token 不该顺带改掉接口行为。</p>
     */
    private Mono<String> ensureAccessToken() {
        return wxAccessTokenProvider.token();
    }

    private Mono<byte[]> requestWxacode(String token, String sceneKey) {
        return requestWxacode(token, sceneKey, false);
    }

    private Mono<byte[]> requestWxacode(String token, String sceneKey, boolean retried) {
        if (!StringUtils.hasText(sceneKey)) {
            return Mono.error(new IllegalStateException("二维码 scene 为空，无法生成小程序码"));
        }
        Map<String, Object> map = new HashMap<>();
        map.put("scene", sceneKey);
        map.put("page", "pages/index/index");
        map.put("width", 280);
        map.put("auto_color", true);
        map.put("is_hyaline", false);
        map.put("check_path", false);
        return WEB_CLIENT.post().uri(GET_WXA_CODE_URL + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Mono.just(JSONObject.toJSONString(map)), String.class)
            .retrieve()
            .bodyToMono(byte[].class)
            .flatMap(bytes -> parseWxacodeResponse(bytes, sceneKey, retried))
            .onErrorMap(this::mapWxApiError);
    }

    private Mono<byte[]> parseWxacodeResponse(byte[] bytes, String sceneKey, boolean retried) {
        if (bytes == null || bytes.length == 0) {
            return Mono.error(new IllegalStateException("微信小程序码接口返回空数据"));
        }
        if (bytes[0] != '{') {
            return Mono.just(bytes);
        }
        try {
            JsonNode err = OBJECT_MAPPER.readTree(bytes);
            if (!err.has("errcode") || err.get("errcode").isNull()) {
                return Mono.error(new IllegalStateException("微信小程序码响应解析失败"));
            }
            int errcode = err.get("errcode").asInt();
            if (errcode == 0) {
                return Mono.error(new IllegalStateException("微信小程序码响应格式异常"));
            }
            String errmsg = err.path("errmsg").asText("未知错误");
            if ((errcode == 40001 || errcode == 42001) && !retried) {
                wxAccessTokenProvider.invalidate();
                return ensureAccessToken()
                    .flatMap(newToken -> requestWxacode(newToken, sceneKey, true));
            }
            return Mono.error(new IllegalStateException("微信小程序码生成失败(" + errcode + ")：" + errmsg));
        } catch (IllegalStateException e) {
            return Mono.error(e);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("微信小程序码响应解析失败"));
        }
    }

    @Override
    public Mono<ServerResponse> getQRCodeInfo(ServerRequest serverRequest) {
        String key = serverRequest.pathVariable("key");
        return qrCodeInfoService.fetchByKey(key).flatMap(info -> {
            if (Objects.isNull(info)) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok().body(Mono.just(info), QRCodeInfo.class);
        });
    }

    private Mono<JsonNode> getAccessToken() {
        return this.getAppConfigsByGroupName("appConfig")
            .switchIfEmpty(Mono.error(new IllegalStateException("未找到 appConfig 配置，请先在插件设置中保存应用配置")))
            .flatMap(config -> {
            JsonNode appInfo = config.get("appInfo");
            if (appInfo == null || appInfo.isNull()) {
                return Mono.error(new IllegalStateException("未找到 appConfig.appInfo 配置"));
            }
            String appId = appInfo.path("appId").asText("");
            String appSecret = appInfo.path("appSecret").asText("");
            if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
                return Mono.error(new IllegalStateException("请先在插件配置中填写小程序 appId 和 appSecret"));
            }
            String url = TOKEN_URL + "?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;
            // 使用 String + 手动解析，避开部分 Spring/Jackson 组合下 bodyToMono(JsonNode.class) 的
            // "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]" 崩溃
            return WEB_CLIENT.get().uri(url).retrieve().bodyToMono(String.class)
                .map(raw -> {
                    try {
                        return OBJECT_MAPPER.readTree(raw);
                    } catch (Exception ex) {
                        throw new IllegalStateException("微信 access_token 响应解析失败", ex);
                    }
                });
        }).onErrorMap(this::mapWxApiError);
    }

    private Mono<Attachment> uploadMedia(String postId, byte[] mediaData) {
        String safeName = postId.replaceAll("[^a-zA-Z0-9_-]", "_");
        FilePart filePart = new SimpleFilePart(
            safeName + ".png", byteArrayToFlux(mediaData), MediaType.IMAGE_PNG);

        return this.getAppConfigsByGroupName("appConfig")
            .switchIfEmpty(Mono.error(new IllegalStateException("未找到 appConfig 配置，请先在插件设置中保存应用配置")))
            .flatMap(config -> {
            JsonNode appInfo = config.get("appInfo");
            if (appInfo == null || appInfo.isNull()) {
                return Mono.error(new IllegalStateException("未找到 appConfig.appInfo 配置"));
            }
            String policyName = appInfo.path("policyName").asText("");
            if (!StringUtils.hasText(policyName)) {
                return Mono.error(new IllegalStateException("未配置附件存储策略 policyName"));
            }
            String fileGroupName = appInfo.path("fileGroupName").asText("");
            return attachmentService.upload("admin", policyName, fileGroupName, filePart, null);
        }).onErrorMap(ex -> ex instanceof IllegalStateException
            ? ex
            : new IllegalStateException("二维码图片上传失败：" + ex.getMessage(), ex));
    }

    private Flux<DataBuffer> byteArrayToFlux(byte[] bytes) {
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        return Flux.just(bytes)
            .map(data -> bufferFactory.wrap(ByteBuffer.wrap(data)));
    }

    private Mono<String> getMediaUrl(String postId, byte[] mediaData) {
        return uploadMedia(postId, mediaData)
            .flatMap(this::getPermalink)
            .switchIfEmpty(Mono.error(new IllegalStateException("二维码图片上传成功但未获取到访问地址")));
    }

    private Mono<String> getPermalink(Attachment attachment) {
        return attachmentService.getPermalink(attachment)
            .flatMap(uri -> Mono.just(uri.toString()))
            .switchIfEmpty(Mono.error(new IllegalStateException("二维码图片访问地址生成失败")));
    }

    private Throwable mapWxApiError(Throwable ex) {
        if (ex instanceof IllegalStateException) {
            return ex;
        }
        if (ex instanceof run.halo.app.extension.exception.ExtensionException extensionException) {
            String reason = extensionException.getReason();
            return new IllegalStateException(StringUtils.hasText(reason)
                ? reason
                : "二维码生成失败，请稍后重试");
        }
        if (ex instanceof WebClientResponseException responseException) {
            return new IllegalStateException(
                "微信接口请求失败(" + responseException.getStatusCode().value() + ")：" + responseException.getMessage());
        }
        if (ex instanceof WebClientRequestException) {
            return new IllegalStateException("微信接口网络异常，请稍后重试");
        }
        return new IllegalStateException(
            StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "二维码生成失败，请稍后重试", ex);
    }
}
