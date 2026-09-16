package pro.uhalo.uni;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.scheme.DigestSubscription;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 扩展类型的注册与摘除必须一一对应。
 *
 * <p>这里守的是一个真出过事故的不变式。早前 {@code start()} 与 {@code stop()} 各写一份类型清单，
 * {@code DigestSubscription} 只注册没摘除；而 Halo 的 {@code Scheme.equals}
 * <b>只比 GroupVersionKind、不比类</b>（插件每次升级都换类加载器），于是残留的旧注册
 * 让下一次 {@code register} 判定「已存在」直接返回，<b>该类型的索引再也不会重建</b>——
 * 读拿不到记录（看着像「没订阅」）、写抛异常回滚，线上表现就是订阅保存一直 500。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UniHaloPluginTest {

    /** 应当注册的扩展类型数量：新增类型时这个数字要跟着改（曾经就是两份清单错位） */
    private static final int EXPECTED_TYPE_COUNT = 10;

    @Mock
    private SchemeManager schemeManager;

    private UniHaloPlugin plugin;

    /** 模拟 Halo 的注册表：按 GVK 定位（与 Halo 的 Scheme.equals 一致） */
    private final List<Scheme> registry = new ArrayList<>();

    @BeforeEach
    void setUp() {
        plugin = new UniHaloPlugin(mock(PluginContext.class), schemeManager);

        lenient().doAnswer(inv -> {
            Class<? extends Extension> type = inv.getArgument(0);
            var scheme = Scheme.buildFromType(type);
            registry.removeIf(existing -> sameGvk(existing, scheme));
            registry.add(scheme);
            return null;
        }).when(schemeManager).register(any(Class.class), any());

        lenient().when(schemeManager.fetch(any(GroupVersionKind.class)))
            .thenAnswer(inv -> {
                var gvk = inv.getArgument(0, GroupVersionKind.class);
                return registry.stream()
                    .filter(scheme -> scheme.groupVersionKind().equals(gvk))
                    .findFirst();
            });

        lenient().doAnswer(inv -> {
            Scheme scheme = inv.getArgument(0);
            registry.removeIf(existing -> sameGvk(existing, scheme));
            return null;
        }).when(schemeManager).unregister(any(Scheme.class));
    }

    @Test
    void startShouldRegisterEveryExtensionType() {
        plugin.start();

        assertThat(registry).hasSize(EXPECTED_TYPE_COUNT);
    }

    /**
     * 注册过的每一种类型都要能被摘掉 —— 尤其是 DigestSubscription。
     *
     * <p>它就是漏摘的那个：漏掉之后索引永远建不起来，订阅功能会以一种
     * 「读返回空、写报 500」的诡异方式坏掉，而且重启插件也不会自愈。</p>
     */
    @Test
    void stopShouldUnregisterEveryRegisteredTypeIncludingSubscription() {
        plugin.start();
        assertThat(registered(DigestSubscription.class)).isTrue();

        plugin.stop();

        assertThat(registered(DigestSubscription.class))
            .as("DigestSubscription 必须被摘掉：残留的注册会让它的索引再也建不起来").isFalse();
        assertThat(registry).as("注册了 %d 种，就必须能摘掉 %d 种",
            EXPECTED_TYPE_COUNT, EXPECTED_TYPE_COUNT).isEmpty();
    }

    /**
     * 启动时要自愈历史残留。
     *
     * <p>旧版本漏摘留下的注册（GVK 相同、类不同——Halo 只比 GVK）会让注册变成空操作。
     * 先在启动时清一遍，装上新版插件就恢复，不必再重启一次 Halo。</p>
     */
    @Test
    void startShouldClearStaleRegistrationFirst() {
        registry.add(Scheme.buildFromType(DigestSubscription.class));

        plugin.start();

        assertThat(registered(DigestSubscription.class)).isTrue();
        assertThat(registry).as("残留应被摘掉后重新注册，而不是让注册被跳过")
            .hasSize(EXPECTED_TYPE_COUNT);
    }

    /** 首次安装、或没有任何注册时停插件不能报错 */
    @Test
    void stopWithoutAnyRegistrationShouldBeQuiet() {
        plugin.stop();

        assertThat(registry).isEmpty();
    }

    private boolean registered(Class<? extends Extension> type) {
        var gvk = GroupVersionKind.fromExtension(type);
        return registry.stream().anyMatch(scheme -> scheme.groupVersionKind().equals(gvk));
    }

    private static boolean sameGvk(Scheme left, Scheme right) {
        return left.groupVersionKind().equals(right.groupVersionKind());
    }
}
