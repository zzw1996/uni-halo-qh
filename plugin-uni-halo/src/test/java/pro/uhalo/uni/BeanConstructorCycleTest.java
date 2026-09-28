package pro.uhalo.uni;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构造器注入不能成环。
 *
 * <p>插件用的是「Lombok 生成构造器 + 全字段 final」的注入方式，一旦两个 Service 互相依赖，
 * Spring 在建 bean 时才会抛 {@code BeanCurrentlyInCreationException}——编译能过、单测也全绿，
 * 只有装上插件启动那一刻才炸（曾经真的这样发出过一个版本：订阅额度服务 ↔ 会员服务）。</p>
 *
 * <p>这个测试把「谁依赖谁」从字节码里读出来做一次环检测，把这类只有运行时才暴露的错
 * 提前到 CI。它不加载 Spring 上下文，因此毫秒级完成。</p>
 */
class BeanConstructorCycleTest {

    private static final List<Class<? extends Annotation>> STEREOTYPES = List.of(
        Component.class, Service.class, Repository.class);

    @Test
    void springBeansShouldNotHaveConstructorDependencyCycles() throws Exception {
        var beans = scanBeans();
        assertThat(beans).as("没扫到任何 bean，说明扫描路径不对，这个测试就白跑了")
            .hasSizeGreaterThan(20);

        var graph = buildGraph(beans);
        var edges = graph.values().stream().mapToInt(Set::size).sum();
        assertThat(edges)
            .as("依赖图里一条边都没有，说明取构造器的方式不对，这个测试会永远通过")
            .isGreaterThan(30);

        var cycle = findCycle(graph);

        assertThat(cycle)
            .as("构造器注入出现循环依赖，插件装上后会启动失败：%s", cycle)
            .isNull();
    }

    /**
     * 环检测本身的自检：喂一个已知的环必须报出来，喂无环图必须安静。
     *
     * <p>没有这一段，上面那个「全绿」可能只是因为图是空的——守卫测试最怕的就是这种事。</p>
     */
    @Test
    void cycleDetectorShouldActuallyDetectCycles() {
        var cyclic = new HashMap<Class<?>, Set<Class<?>>>();
        cyclic.put(NodeA.class, new LinkedHashSet<>(Set.of(NodeB.class)));
        cyclic.put(NodeB.class, new LinkedHashSet<>(Set.of(NodeC.class)));
        cyclic.put(NodeC.class, new LinkedHashSet<>(Set.of(NodeA.class)));

        assertThat(findCycle(cyclic))
            .as("三节点的环必须被发现")
            .isNotNull()
            .isNotEmpty();

        var acyclic = new HashMap<Class<?>, Set<Class<?>>>();
        acyclic.put(NodeA.class, new LinkedHashSet<>(Set.of(NodeB.class, NodeC.class)));
        acyclic.put(NodeB.class, new LinkedHashSet<>(Set.of(NodeC.class)));
        acyclic.put(NodeC.class, new LinkedHashSet<>());

        assertThat(findCycle(acyclic)).isNull();
    }

    private static final class NodeA {
    }

    private static final class NodeB {
    }

    private static final class NodeC {
    }

    /**
     * 扫描插件自己的类，挑出被 Spring 注册为 bean 的那些。
     *
     * <p>锚点必须是<b>主产物</b>里的类（用插件主类定位）：测试类自己的 code source 是测试产物目录，
     * 那里只有测试类，扫出来的 bean 数量为 0，这个测试就形同虚设。</p>
     */
    private static Set<Class<?>> scanBeans() throws Exception {
        var root = Paths.get(UniHaloPlugin.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        var packageDir = root.resolve("pro/uhalo/uni");
        assertThat(Files.isDirectory(packageDir))
            .as("找不到编译产物目录：%s", packageDir)
            .isTrue();

        var beans = new LinkedHashSet<Class<?>>();
        try (Stream<Path> paths = Files.walk(packageDir)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var relative = root.relativize(path).toString()
                    .replace('\\', '/')
                    .replaceAll("\\.class$", "")
                    .replace('/', '.');
                if (relative.contains("$")) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(relative, false,
                        BeanConstructorCycleTest.class.getClassLoader());
                } catch (Throwable ignored) {
                    continue;
                }
                if (isBean(type)) {
                    beans.add(type);
                }
            }
        }
        return beans;
    }

    private static boolean isBean(Class<?> type) {
        if (type.isInterface() || type.isEnum() || java.lang.reflect.Modifier.isAbstract(
            type.getModifiers())) {
            return false;
        }
        for (var type_ : type.getAnnotations()) {
            for (var stereotype : STEREOTYPES) {
                if (stereotype.isInstance(type_)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 建图：只保留「bean → bean」的构造器依赖。
     *
     * <p>Spring 在只有一个构造器时直接用它做注入，多个构造器时只认 {@code @Autowired} 的那个——
     * 与这里的取法一致。</p>
     */
    static Map<Class<?>, Set<Class<?>>> buildGraph(Set<Class<?>> beans) {
        var graph = new HashMap<Class<?>, Set<Class<?>>>();
        for (var bean : beans) {
            graph.put(bean, new LinkedHashSet<>());
            var constructor = injectedConstructor(bean);
            if (constructor == null) {
                continue;
            }
            for (var parameter : constructor.getParameterTypes()) {
                if (beans.contains(parameter)) {
                    graph.get(bean).add(parameter);
                }
            }
        }
        return graph;
    }

    private static Constructor<?> injectedConstructor(Class<?> bean) {
        var constructors = bean.getDeclaredConstructors();
        if (constructors.length == 1) {
            return constructors[0];
        }
        for (var constructor : constructors) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                return constructor;
            }
        }
        return null;
    }

    /** 深度优先找环；返回环上的类名（便于直接定位），没有则返回 null */
    static List<String> findCycle(Map<Class<?>, Set<Class<?>>> graph) {
        var visited = new HashSet<Class<?>>();
        var inStack = new LinkedHashSet<Class<?>>();
        for (var node : graph.keySet()) {
            var cycle = walk(node, graph, visited, inStack);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static List<String> walk(Class<?> node, Map<Class<?>, Set<Class<?>>> graph,
        Set<Class<?>> visited, LinkedHashSet<Class<?>> inStack) {
        if (inStack.contains(node)) {
            var path = new ArrayList<String>();
            var started = false;
            for (var item : inStack) {
                if (item.equals(node)) {
                    started = true;
                }
                if (started) {
                    path.add(item.getSimpleName());
                }
            }
            path.add(node.getSimpleName());
            return path;
        }
        if (!visited.add(node)) {
            return null;
        }
        inStack.add(node);
        for (var next : graph.getOrDefault(node, Set.of())) {
            var cycle = walk(next, graph, visited, inStack);
            if (cycle != null) {
                return cycle;
            }
        }
        inStack.remove(node);
        return null;
    }
}
