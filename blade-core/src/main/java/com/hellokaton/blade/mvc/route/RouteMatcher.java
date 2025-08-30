package com.hellokaton.blade.mvc.route;

import com.hellokaton.blade.ioc.annotation.Order;
import com.hellokaton.blade.kit.*;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.http.HttpMethod;
import com.hellokaton.blade.mvc.route.mapping.dynamic.TrieMapping;
import com.hellokaton.blade.mvc.ui.ResponseType;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hellokaton.blade.kit.BladeKit.logAddRoute;

/**
 * Default Route Matcher
 *
 * @author <a href="mailto:hellokaton@gmail.com" target="_blank">hellokaton</a>
 * @since 2.1.2.RELEASE
 */
@Slf4j
public class RouteMatcher {

    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("/(?:([^:/]*):([^/]+))|(\\.\\*)");
    private static final String METHOD_NAME = "handle";

    // Storage URL and route
    private final Map<String, Route> routes = new HashMap<>(16);
    private final Map<String, List<Route>> hooks = new java.util.concurrent.ConcurrentHashMap<>(8);
    private final java.util.concurrent.atomic.AtomicLong orderSeq = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.CopyOnWriteArrayList<Route> middleware = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Method[]> classMethodPool = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> controllerPool = new ConcurrentHashMap<>(8);

    private final DynamicMapping routeMapping = new TrieMapping();

    private Route addRoute(HttpMethod httpMethod, String path, RouteHandler handler) throws NoSuchMethodException {
        Class<?> handleType = handler.getClass();
        Method method = handleType.getMethod(RouteMatcher.METHOD_NAME, RouteContext.class);
        return addRoute(httpMethod, path, handler, RouteHandler.class, method, null);
    }

    Route addRoute(Route route) {
        String path = route.getPath();
        HttpMethod httpMethod = route.getHttpMethod();
        Object controller = route.getTarget();
        Class<?> controllerType = route.getTargetType();
        Method method = route.getAction();
        ResponseType responseType = route.getResponseType();
        return addRoute(httpMethod, path, controller, controllerType, method, responseType);
    }

    private Route addRoute(HttpMethod httpMethod, String path, Object controller,
                           Class<?> controllerType, Method method, ResponseType responseType) {

        String key = path + "#" + httpMethod.name();

        // exist
        if (this.routes.containsKey(key)) {
            log.warn("\tRoute {} -> {} has exist", path, httpMethod);
        }

        Route route = new Route(httpMethod, path, controller, controllerType, method, responseType);
        if (BladeKit.isWebHook(httpMethod)) {
            Order order = controllerType.getAnnotation(Order.class);
            if (null != order) {
                route.setSort(order.value());
            }
            this.hooks.compute(key, (k, v) -> {
                List<Route> list = v == null ? new java.util.concurrent.CopyOnWriteArrayList<>() : v;
                list.add(route);
                return list;
            });
        } else {
            this.routes.put(key, route);
        }
        route.setRegisterOrder(orderSeq.incrementAndGet());
        return route;
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod) {
        return addRoute(path, handler, httpMethod, null);
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod,
                          com.hellokaton.blade.mvc.hook.WebHookOptions options) {
        try {
            Route route = addRoute(httpMethod, path, handler);
            if (null != route && BladeKit.isWebHook(httpMethod)) {
                com.hellokaton.blade.mvc.hook.WebHookOptions opts = options == null ?
                        new com.hellokaton.blade.mvc.hook.WebHookOptions() : options;
                opts.addIncludes(path);
                bindOptions(route, opts);
            } else if (null != route) {
                route.setRegisterOrder(orderSeq.incrementAndGet());
            }
            return route;
        } catch (Exception e) {
            log.error("", e);
        }
        return null;
    }

    private void bindOptions(Route route, com.hellokaton.blade.mvc.hook.WebHookOptions options) {
        options.setOrder(orderSeq.incrementAndGet());
        route.setWebHookOptions(options);
        route.setRegisterOrder(options.getOrder());
    }

    public void route(String path, Class<?> clazz, String methodName) {
        Assert.notNull(methodName, "Method name not is null");
        HttpMethod httpMethod = HttpMethod.ALL;
        if (methodName.contains(":")) {
            String[] methodArr = methodName.split(":");
            httpMethod = HttpMethod.valueOf(methodArr[0].toUpperCase());
            methodName = methodArr[1];
        }
        this.route(path, clazz, methodName, httpMethod);
    }

    public void route(String path, Class<?> clazz, String methodName, HttpMethod httpMethod) {
        try {
            Assert.notNull(path, "Route path not is null.");
            Assert.notNull(clazz, "Route type not is null.");
            Assert.notNull(methodName, "Method name not is null.");
            Assert.notNull(httpMethod, "Request Method not is null.");

            Method[] methods = classMethodPool.computeIfAbsent(clazz.getName(), k -> clazz.getMethods());
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    Object controller = controllerPool.computeIfAbsent(clazz, k -> ReflectKit.newInstance(clazz));
                    this.addRoute(httpMethod, path, controller, clazz, method, null);
                }
            }
        } catch (Exception e) {
            log.error("Add route method error", e);
        }
    }

    public Route lookupRoute(String httpMethod, String path) {
        return routeMapping.findRoute(httpMethod, path);
    }

    private String cleanPathVariable(String pathVariable) {
        if (pathVariable.contains(".")) {
            return pathVariable.substring(0, pathVariable.indexOf('.'));
        }
        return pathVariable;
    }

    public boolean hasBeforeHook() {
        return hooks.values().stream()
                .flatMap(Collection::stream).anyMatch(route -> route.getHttpMethod().equals(HttpMethod.BEFORE));
    }

    public boolean hasAfterHook() {
        return hooks.values().stream()
                .flatMap(Collection::stream).anyMatch(route -> route.getHttpMethod().equals(HttpMethod.AFTER));
    }

    /**
     * Find all in before of the hook
     *
     * @param path request path
     */
    public List<Route> getBefore(String path) {
        String cleanPath = PathKit.normalize(path);

        List<Route> collect = hooks.values().stream()
                .flatMap(Collection::stream)
                .filter(route -> route.getHttpMethod() == HttpMethod.BEFORE)
                .filter(route -> {
                    com.hellokaton.blade.mvc.hook.WebHookOptions opt = route.getWebHookOptions();
                    return null == opt || opt.matchPath(cleanPath);
                })
                .sorted(Comparator
                        .comparingInt((Route r) -> {
                            com.hellokaton.blade.mvc.hook.WebHookOptions opt = r.getWebHookOptions();
                            return null == opt ? 0 : opt.getPriority();
                        })
                        .thenComparingLong(Route::getRegisterOrder))
                .collect(Collectors.toList());

        this.giveMatch(cleanPath, collect);
        return collect;
    }

    /**
     * Find all in after of the hooks
     *
     * @param path request path
     */
    public List<Route> getAfter(String path) {
        String cleanPath = PathKit.normalize(path);

        List<Route> afters = hooks.values().stream()
                .flatMap(Collection::stream)
                .filter(route -> route.getHttpMethod() == HttpMethod.AFTER)
                .filter(route -> {
                    com.hellokaton.blade.mvc.hook.WebHookOptions opt = route.getWebHookOptions();
                    return null == opt || opt.matchPath(cleanPath);
                })
                .sorted(Comparator.comparingLong(Route::getRegisterOrder))
                .collect(Collectors.toList());

        this.giveMatch(cleanPath, afters);
        return afters;
    }

    public List<Route> getMiddleware() {
        return this.middleware;
    }

    public int middlewareCount() {
        return this.middleware.size();
    }

    /**
     * Sort of path
     *
     * @param uri    request uri
     * @param routes route list
     */
    private void giveMatch(final String uri, List<Route> routes) {
        routes.stream().sorted((o1, o2) -> {
            if (o2.getPath().equals(uri)) {
                return o2.getPath().indexOf(uri);
            }
            return -1;
        });
    }

    /**
     * Parse PathKit
     *
     * @param path route path
     * @return return parsed path
     */
    private String parsePath(String path) {
        path = PathKit.fixPath(path);
        try {
            URI uri = new URI(path);
            return uri.getPath();
        } catch (URISyntaxException e) {
            return path;
        }
    }

    /**
     * register route to container
     */
    public void register() {
        routes.values().forEach(route -> logAddRoute(log, route));
        hooks.values().stream().flatMap(Collection::stream).forEach(route -> logAddRoute(log, route));

        routes.values().forEach(this::registerRoute);
    }

    private void registerRoute(Route route) {
        String path = parsePath(route.getPath());
        Matcher matcher = null;
        if (path != null) {
            matcher = PATH_VARIABLE_PATTERN.matcher(path);
        }
        boolean find = false;
        List<String> uriVariableNames = new ArrayList<>();
        while (matcher != null && matcher.find()) {
            if (!find) {
                find = true;
            }
            String regexName = matcher.group(1);
            String regexValue = matcher.group(2);

            // when /** is matched, there is no param
            if (regexName == null && regexValue == null) {
                continue;
            }

            // just a simple path param
            if (StringKit.isBlank(regexName)) {
                uriVariableNames.add(regexValue);
            } else {
                //regex path param
                uriVariableNames.add(regexName);
            }
        }
        HttpMethod httpMethod = route.getHttpMethod();
        if (HttpMethod.BEFORE == httpMethod || HttpMethod.AFTER == httpMethod) {
        } else {
            routeMapping.addRoute(httpMethod, route, uriVariableNames);
        }
    }

    public void addMiddleware(WebHook webHook) {
        addMiddleware(webHook, null);
    }

    public void addMiddleware(WebHook webHook, com.hellokaton.blade.mvc.hook.WebHookOptions options) {
        Method method = ReflectKit.getMethod(WebHook.class, HttpMethod.BEFORE.name().toLowerCase(), RouteContext.class);
        Route route = new Route(HttpMethod.BEFORE, "/**", webHook, WebHook.class, method, null);
        com.hellokaton.blade.mvc.hook.WebHookOptions opts = options == null ?
                new com.hellokaton.blade.mvc.hook.WebHookOptions().addIncludes("/**") : options;
        for (Route r : middleware) {
            if (r.getTarget() == webHook && opts.equals(r.getWebHookOptions())) {
                return;
            }
        }
        bindOptions(route, opts);
        this.middleware.add(route);
    }

}
