package pro.uhalo.uni;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.PointsRecord;
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

        schemeManager.register(WxUser.class, indexSpecs -> indexSpecs.add(new IndexSpec()
            .setName("openid")
            .setIndexFunc(simpleAttribute(WxUser.class, WxUser::getOpenid))));

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

        log.info("【UniHalo】插件启动成功！");
    }

    @Override
    public void stop() {

        schemeManager.unregister(schemeManager.get(QRCodeInfo.class));
        schemeManager.unregister(schemeManager.get(WxUser.class));
        schemeManager.unregister(schemeManager.get(UserActivity.class));
        schemeManager.unregister(schemeManager.get(UserPoints.class));
        schemeManager.unregister(schemeManager.get(PointsRecord.class));

        log.info("【UniHalo】插件停止！");
    }
}
