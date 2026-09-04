package pro.uhalo.uni.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.HaloBindMailService;
import pro.uhalo.uni.utils.EndpointErrorResolver;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.GroupVersion;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

@Service
@Slf4j
public class HaloBindMailServiceImpl implements HaloBindMailService {

    private static final String EMAIL_VERIFICATION_REASON_TYPE = "email-verification";

    private final ObjectProvider<NotificationReasonEmitter> reasonEmitterProvider;
    private final ObjectProvider<NotificationCenter> notificationCenterProvider;

    public HaloBindMailServiceImpl(ObjectProvider<NotificationReasonEmitter> reasonEmitterProvider,
                                   ObjectProvider<NotificationCenter> notificationCenterProvider) {
        this.reasonEmitterProvider = reasonEmitterProvider;
        this.notificationCenterProvider = notificationCenterProvider;
    }

    @Override
    public Mono<Void> sendBindCode(String haloUserName, String toEmail, String code) {
        if (!StringUtils.hasText(haloUserName)) {
            return Mono.error(new WxAuthException("Halo 账号不存在"));
        }
        if (!StringUtils.hasText(toEmail)) {
            return Mono.error(new WxAuthException("Halo 用户未绑定邮箱"));
        }
        var reasonEmitter = reasonEmitterProvider.getIfAvailable();
        var notificationCenter = notificationCenterProvider.getIfAvailable();
        if (reasonEmitter == null || notificationCenter == null) {
            return Mono.error(new WxAuthException(
                "Halo 通知服务不可用，请确认 Halo 版本 ≥ 2.10 且已启用邮件通知"));
        }
        var normalizedUserName = haloUserName.trim();
        var normalizedEmail = toEmail.trim();
        log.info("Sending Halo bind code via email-verification notification, haloUserName={}, email={}",
            normalizedUserName, normalizedEmail);
        return autoSubscribeVerificationEmailNotification(notificationCenter, normalizedEmail)
            .then(emitVerificationNotification(reasonEmitter, normalizedUserName, normalizedEmail, code))
            .doOnSuccess(v -> log.info(
                "Halo bind code notification emitted, haloUserName={}, email={}",
                normalizedUserName, normalizedEmail))
            .doOnError(ex -> log.error(
                "Failed to emit Halo bind code notification, haloUserName={}, email={}",
                normalizedUserName, normalizedEmail, ex))
            .onErrorMap(ex -> {
                var reason = EndpointErrorResolver.findWxAuthReason(ex);
                if (StringUtils.hasText(reason)) {
                    return new WxAuthException(reason);
                }
                return new WxAuthException("验证码邮件发送失败：" + resolveMessage(ex));
            });
    }

    private Mono<Void> emitVerificationNotification(NotificationReasonEmitter reasonEmitter,
                                                    String haloUserName,
                                                    String email,
                                                    String code) {
        var interestReasonSubject = createInterestReason(email).getSubject();
        return reasonEmitter.emit(EMAIL_VERIFICATION_REASON_TYPE, builder -> builder
            .attribute("code", code)
            .attribute("expirationAtMinutes", Constants.HALO_BIND_CODE_EXPIRE_SECONDS / 60)
            .attribute("username", haloUserName)
            .author(UserIdentity.of(haloUserName))
            .subject(Reason.Subject.builder()
                .apiVersion(interestReasonSubject.getApiVersion())
                .kind(interestReasonSubject.getKind())
                .name(interestReasonSubject.getName())
                .title("绑定 Halo 账号：" + email)
                .build()));
    }

    private Mono<Void> autoSubscribeVerificationEmailNotification(NotificationCenter notificationCenter,
                                                                  String email) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        return notificationCenter.subscribe(subscriber, createInterestReason(email)).then();
    }

    private Subscription.InterestReason createInterestReason(String email) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(EMAIL_VERIFICATION_REASON_TYPE);
        interestReason.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion(new GroupVersion(User.GROUP, User.VERSION).toString())
            .kind(User.KIND)
            .name(UserIdentity.anonymousWithEmail(email).name())
            .build());
        return interestReason;
    }

    private static String resolveMessage(Throwable ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
