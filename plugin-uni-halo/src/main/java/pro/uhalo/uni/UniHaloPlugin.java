package pro.uhalo.uni;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.ContentUnlock;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.scheme.LuckyWinning;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.QRCodeInfo;
import pro.uhalo.uni.scheme.UserActivity;
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

    private final SchemeManager schemeManager;

    public UniHaloPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {

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

        schemeManager.register(LuckyWinning.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("wxOpenid")
                .setIndexFunc(simpleAttribute(LuckyWinning.class, LuckyWinning::getWxOpenid)));
            indexSpecs.add(new IndexSpec()
                .setName("status")
                .setIndexFunc(simpleAttribute(LuckyWinning.class, LuckyWinning::getStatus)));
            log.info("【UniHalo】LuckyWinning scheme 注册成功");
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

        log.info("【UniHalo】插件启动成功！");
    }

    @Override
    public void stop() {

        schemeManager.unregister(schemeManager.get(QRCodeInfo.class));
        schemeManager.unregister(schemeManager.get(WxUser.class));
        schemeManager.unregister(schemeManager.get(UserActivity.class));
        schemeManager.unregister(schemeManager.get(UserPoints.class));
        schemeManager.unregister(schemeManager.get(PointsRecord.class));
        schemeManager.unregister(schemeManager.get(PayOrder.class));
        schemeManager.unregister(schemeManager.get(LuckyWinning.class));
        schemeManager.unregister(schemeManager.get(ContentUnlock.class));
        schemeManager.unregister(schemeManager.get(DigestReport.class));

        log.info("【UniHalo】插件停止！");
    }
}
