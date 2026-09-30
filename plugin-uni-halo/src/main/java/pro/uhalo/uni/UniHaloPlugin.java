package pro.uhalo.uni;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.scheme.ContentUnlock;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.scheme.DigestSeenArticle;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.PushRecord;
import pro.uhalo.uni.scheme.PushState;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.QRCodeInfo;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserAppSettings;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpec;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

import static run.halo.app.extension.index.IndexAttributeFactory.simpleAttribute;

/**
 * @author 小莫唐尼
 */
@Slf4j
@Component
public class UniHaloPlugin extends BasePlugin {

    /**
     * 本插件注册的全部扩展类型。
     *
     * <p><b>注册与摘除必须共用这一份清单</b>：早前 start() 里逐个注册、stop() 里逐行摘除，
     * 两份清单各写各的，结果 DigestSubscription 只注册没摘除——埋了一个很难查的坑：
     * Halo 的 {@code Scheme.equals} <b>只比 GroupVersionKind、不比类</b>，
     * 而插件每次升级都会换类加载器，于是残留的旧 scheme 会让下次 register 判定为
     * 「已存在」直接返回，<b>这个类型的索引从此再也不会重建</b>。
     * 表现是：读走索引 → 拿不到任何记录（看着像「没订阅」），
     * 写走索引 → 找不到索引 → 抛异常回滚 → 接口 500，且一条记录都写不进去。</p>
     */
    private static final java.util.List<Class<? extends run.halo.app.extension.Extension>>
        EXTENSION_TYPES = java.util.List.of(
            QRCodeInfo.class, WxUser.class, UserActivity.class, UserPoints.class,
            PointsRecord.class, PayOrder.class, ContentUnlock.class,
            DigestSubscription.class, DigestReport.class,
            AiContentPreference.class, AiContentIssue.class,
            UserAppSettings.class,
            PushSubscription.class, PushRecord.class, PushState.class,
            DigestSeenArticle.class);

    private final SchemeManager schemeManager;

    public UniHaloPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        // 注册之前先把可能残留的旧 scheme 清掉。
        //
        // 必须做这一步：只要历史上漏摘过一次（旧版本的 bug），那份残留就会让下面
        // 每一个 register 都变成空操作，索引也就永远建不起来——而插件自己完全看不出来，
        // 只有等某个功能写数据时才会以 500 的形式暴露出来。这里主动清一遍，
        // 装上新版插件就自愈，不需要重启 Halo。
        clearStaleSchemes();

        schemeManager.register(QRCodeInfo.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                    .setName("key")
                    .setIndexFunc(
                            simpleAttribute(QRCodeInfo.class,
                                    QRCodeInfo::getKey)));
            indexSpecs.add(new IndexSpec()
                    .setName("postId")
                    .setIndexFunc(
                            simpleAttribute(QRCodeInfo.class,
                                    QRCodeInfo::getPostId)));
        });

        schemeManager.register(WxUser.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("openid")
                .setIndexFunc(simpleAttribute(WxUser.class, WxUser::getOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("inviteCode")
                .setIndexFunc(simpleAttribute(WxUser.class, WxUser::getInviteCode)));
            indexSpecs.add(new IndexSpec()
                .setName("inviterOpenid")
                .setIndexFunc(simpleAttribute(WxUser.class, WxUser::getInviterOpenid)));
        });

        schemeManager.register(UserActivity.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(UserActivity.class, UserActivity::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("type")
                .setIndexFunc(simpleAttribute(UserActivity.class, UserActivity::getType)));
            indexSpecs.add(new IndexSpec()
                .setName("articleName")
                .setIndexFunc(simpleAttribute(UserActivity.class, UserActivity::getArticleName)));
            indexSpecs.add(new IndexSpec()
                .setName("contentType")
                .setIndexFunc(simpleAttribute(UserActivity.class, UserActivity::getContentType)));
            indexSpecs.add(new IndexSpec()
                .setName("activityKey")
                .setIndexFunc(simpleAttribute(UserActivity.class, UserActivity::getActivityKey)));
            log.info("【UniHalo】UserActivity scheme 注册成功");
        });

        schemeManager.register(UserPoints.class, indexSpecs -> indexSpecs.add(new IndexSpec()
            .setName("wxOpenid")
            .setIndexFunc(simpleAttribute(UserPoints.class, UserPoints::getWxOpenid))));
        log.info("【UniHalo】UserPoints scheme 注册成功");

        schemeManager.register(PointsRecord.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(PointsRecord.class, PointsRecord::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("earnKey")
                .setIndexFunc(simpleAttribute(PointsRecord.class, PointsRecord::getEarnKey)));
            indexSpecs.add(new IndexSpec()
                .setName("action")
                .setIndexFunc(simpleAttribute(PointsRecord.class, PointsRecord::getAction)));
            indexSpecs.add(new IndexSpec()
                .setName("wxUserId")
                .setIndexFunc(simpleAttribute(PointsRecord.class, PointsRecord::getWxUserId)));
            log.info("【UniHalo】PointsRecord scheme 注册成功");
        });

        schemeManager.register(PayOrder.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("outTradeNo")
                .setIndexFunc(simpleAttribute(PayOrder.class, PayOrder::getOutTradeNo)));
            indexSpecs.add(new IndexSpec()
                .setName("wxOrderId")
                .setIndexFunc(simpleAttribute(PayOrder.class, PayOrder::getWxOrderId)));
            indexSpecs.add(new IndexSpec()
                .setName("openid")
                .setIndexFunc(simpleAttribute(PayOrder.class, PayOrder::getOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("status")
                .setIndexFunc(simpleAttribute(PayOrder.class, PayOrder::getStatus)));
            log.info("【UniHalo】PayOrder scheme 注册成功");
        });

        schemeManager.register(ContentUnlock.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("unlockKey")
                .setIndexFunc(simpleAttribute(ContentUnlock.class, ContentUnlock::getUnlockKey)));
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(ContentUnlock.class, ContentUnlock::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("articleName")
                .setIndexFunc(simpleAttribute(ContentUnlock.class, ContentUnlock::getArticleName)));
            log.info("【UniHalo】ContentUnlock scheme 注册成功");
        });

        schemeManager.register(DigestSubscription.class, indexSpecs -> {
            // 订阅按用户查询（一个用户一条记录，资源名由 openid 派生）
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(DigestSubscription.class,
                    DigestSubscription::getWxOpenid)));
            log.info("【UniHalo】DigestSubscription scheme 注册成功");
        });

        schemeManager.register(DigestReport.class, indexSpecs -> {
            // date 是定时任务的幂等键（按天查已有记录），status 供后台筛选
            indexSpecs.add(new IndexSpec()
                .setName("date")
                .setIndexFunc(simpleAttribute(DigestReport.class, DigestReport::getDate)));
            indexSpecs.add(new IndexSpec()
                .setName("status")
                .setIndexFunc(simpleAttribute(DigestReport.class, DigestReport::getStatus)));
            log.info("【UniHalo】DigestReport scheme 注册成功");
        });

        schemeManager.register(DigestSeenArticle.class, indexSpecs -> {
            // 跨天去重按「最近 14 天」的日期集合滚动查询（in），不需要按 dedupKey 查
            indexSpecs.add(new IndexSpec()
                .setName("seenDate")
                .setIndexFunc(simpleAttribute(DigestSeenArticle.class,
                    DigestSeenArticle::getSeenDate)));
            log.info("【UniHalo】DigestSeenArticle scheme 注册成功");
        });

        schemeManager.register(AiContentPreference.class, indexSpecs -> {
            // 偏好按用户查询（一个用户一条记录，资源名由 openid 派生）
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(AiContentPreference.class,
                    AiContentPreference::getWxOpenid)));
            log.info("【UniHalo】AiContentPreference scheme 注册成功");
        });

        schemeManager.register(AiContentIssue.class, indexSpecs -> {
            // 简报按「用户 + 日期」查询：既是「每天一份」的幂等键，也是成本阀门
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(AiContentIssue.class, AiContentIssue::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("date")
                .setIndexFunc(simpleAttribute(AiContentIssue.class, AiContentIssue::getDate)));
            log.info("【UniHalo】AiContentIssue scheme 注册成功");
        });

        schemeManager.register(UserAppSettings.class, indexSpecs -> {
            // 应用设置副本按用户查询（一个用户一条记录，资源名由 openid 派生）
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(UserAppSettings.class,
                    UserAppSettings::getWxOpenid)));
            log.info("【UniHalo】UserAppSettings scheme 注册成功");
        });

        schemeManager.register(PushSubscription.class, indexSpecs -> {
            // 推送额度按用户查询（一个用户一条记录，资源名由 openid 派生）
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(PushSubscription.class,
                    PushSubscription::getWxOpenid)));
            log.info("【UniHalo】PushSubscription scheme 注册成功");
        });

        schemeManager.register(PushRecord.class, indexSpecs -> {
            // 推送记录：按用户 + 类型查询（后台看失败原因），幂等靠资源名
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(PushRecord.class, PushRecord::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("kind")
                .setIndexFunc(simpleAttribute(PushRecord.class, PushRecord::getKind)));
            log.info("【UniHalo】PushRecord scheme 注册成功");
        });

        schemeManager.register(PushState.class, indexSpecs -> {
            // 单例状态（水位 + 当日计数）：按固定资源名读取，不需要索引
            log.info("【UniHalo】PushState scheme 注册成功");
        });

        log.info("【UniHalo】插件启动成功！");
    }

    @Override
    public void stop() {
        // 与 start() 用同一份清单，别再出现「注册了却忘了摘」——那正是订阅保存 500 的根因
        EXTENSION_TYPES.forEach(this::unregisterQuietly);
        log.info("【UniHalo】插件停止！");
    }

    /**
     * 摘除某个扩展类型的 scheme。
     *
     * <p>两处讲究：</p>
     * <ul>
     *   <li><b>按 GVK 查找，不用 {@code get(Class)}</b>：残留的 scheme 其 type 属于上一次
     *       插件加载的类加载器，用 Class 去找会抛「找不到 scheme」，把 stop() 打断，
     *       后面的类型就都摘不掉了；GVK 是一串字符串，换类加载器也照样能定位。</li>
     *   <li><b>找不到就跳过（{@code fetch} 返回 Optional）</b>：首次安装时本来就没有旧注册，
     *       这不是错误。</li>
     * </ul>
     */
    private void unregisterQuietly(Class<? extends run.halo.app.extension.Extension> type) {
        var gvk = run.halo.app.extension.GroupVersionKind.fromExtension(type);
        schemeManager.fetch(gvk).ifPresent(schemeManager::unregister);
    }

    /** 清掉本插件这些类型上残留的旧注册（旧版本漏摘、以及插件重载留下的） */
    private void clearStaleSchemes() {
        for (var type : EXTENSION_TYPES) {
            var gvk = run.halo.app.extension.GroupVersionKind.fromExtension(type);
            schemeManager.fetch(gvk).ifPresent(scheme -> {
                log.info("【UniHalo】发现残留的 scheme（{}），先摘掉再重新注册：{}",
                    type.getSimpleName(), gvk);
                schemeManager.unregister(scheme);
            });
        }
    }
}
