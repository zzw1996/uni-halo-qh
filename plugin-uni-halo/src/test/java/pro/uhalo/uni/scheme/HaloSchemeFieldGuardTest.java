package pro.uhalo.uni.scheme;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.infra.MpIssueStore;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.app.extension.GroupVersionKind;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扩展类字段命名护栏：<b>不得声明与 Halo 扩展运算符同名的方法/字段</b>。
 *
 * <p>线上踩过两次同一个坑：扩展类里声明 {@code kind} 字段时，Lombok 生成的
 * {@code getKind()/setKind()} 会覆盖 {@code AbstractExtension} 的实现，而 Halo
 * 把扩展转成 {@code Unstructured}/{@code groupVersionKind()} 时读的正是实例的
 * {@code apiVersion + kind}——于是 Halo 拿业务字段（{@code ISSUE} / {@code newPost}）
 * 当扩展类型去查 scheme，写入直接失败：
 * {@code Scheme not found for uni.uhalo.pro/v1alpha1/ISSUE}。</p>
 *
 * <p>这个测试会扫描 {@code pro.uhalo.uni.scheme} 包下所有带 {@code @GVK} 的类，
 * 新增扩展类型时自动纳入检查；两个历史上的踩坑类额外单列断言，
 * 保证扫描不到文件的环境里也拦得住。</p>
 */
class HaloSchemeFieldGuardTest {

    /** Halo 的 ExtensionOperator / MetadataOperator 方法名：子类字段绝不能生成同名访问器 */
    private static final Set<String> RESERVED_METHODS = Set.of(
        "getKind", "setKind", "getApiVersion", "setApiVersion",
        "getMetadata", "setMetadata", "groupVersionKind",
        "getName", "setName", "getGenerateName", "setGenerateName",
        "getLabels", "setLabels", "getAnnotations", "setAnnotations",
        "getVersion", "setVersion", "getCreationTimestamp", "setCreationTimestamp",
        "getDeletionTimestamp", "setDeletionTimestamp", "getFinalizers", "setFinalizers");

    @Test
    void everySchemeClassShouldAvoidHaloOperatorProperties() {
        var offenders = new ArrayList<String>();
        for (var type : schemeClasses()) {
            var declared = Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .filter(RESERVED_METHODS::contains)
                .collect(Collectors.toSet());
            if (!declared.isEmpty()) {
                offenders.add(type.getSimpleName() + " → " + declared);
            }
        }
        assertThat(offenders).as("这些字段会覆盖 Halo 基类方法，导致扩展 GVK 被顶掉、写入 500")
            .isEmpty();
    }

    @Test
    void knownRiskClassesShouldNotShadowKind() {
        assertNotShadowing(MpIssue.class, MpIssueStore.KIND_ISSUE, MpIssueStore.KIND_BACKFILL);
        assertNotShadowing(PushRecord.class, "newPost", "aiDigest");
    }

    @Test
    void registeredGvkShouldBeMpIssue() {
        var gvk = GroupVersionKind.fromExtension(MpIssue.class);
        assertThat(gvk.kind()).isEqualTo("MpIssue");
        assertThat(gvk.group()).isEqualTo("uni.uhalo.pro");
        assertThat(GroupVersionKind.fromExtension(PushRecord.class).kind()).isEqualTo("PushRecord");
    }

    /** 业务值写进去之后，实例对外声明的扩展类型不能跟着变 */
    private static void assertNotShadowing(Class<? extends AbstractExtension> type,
        String... businessValues) {
        var declaredKind = GroupVersionKind.fromExtension(type).kind();
        for (var value : businessValues) {
            try {
                var instance = type.getDeclaredConstructor().newInstance();
                // 业务值写在子类自己的字段上（sourceKind）
                type.getMethod("setSourceKind", String.class).invoke(instance, value);
                var kind = instance.getKind();
                if (value.equals(kind)) {
                    throw new AssertionError(type.getSimpleName() + " 的 sourceKind 泄漏进了 getKind()："
                        + kind + "（Halo 会用它查 scheme，写入必失败）");
                }
                assertThat(kind == null || declaredKind.equals(kind))
                    .as("%s 的 getKind() 只能是 null 或 %s，实际是 %s",
                        type.getSimpleName(), declaredKind, kind)
                    .isTrue();
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(type.getSimpleName() + " 无法反射检查：" + ex.getMessage(), ex);
            }
        }
    }

    /** 扫描 scheme 包下所有带 @GVK 的扩展类（新增类型自动纳入） */
    private static List<Class<?>> schemeClasses() {
        var packagePath = "pro.uhalo.uni.scheme";
        var url = HaloSchemeFieldGuardTest.class.getClassLoader()
            .getResource(packagePath.replace('.', '/'));
        if (url == null || !"file".equals(url.getProtocol())) {
            return List.of(MpIssue.class, PushRecord.class);
        }
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            var result = new ArrayList<Class<?>>();
            for (var path : files.toList()) {
                var name = path.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                var simpleName = name.substring(0, name.length() - ".class".length());
                Class<?> type;
                try {
                    type = Class.forName(packagePath + "." + simpleName);
                } catch (ClassNotFoundException ex) {
                    continue;
                }
                if (type.isAnnotationPresent(GVK.class)
                    && AbstractExtension.class.isAssignableFrom(type)) {
                    result.add(type);
                }
            }
            return result.isEmpty() ? List.of(MpIssue.class, PushRecord.class) : List.copyOf(result);
        } catch (Exception ex) {
            // 扫描不可用（打包运行等）时退回两个已知风险类，不让护栏静默失效
            return List.of(MpIssue.class, PushRecord.class);
        }
    }
}
