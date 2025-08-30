package com.hellokaton.blade.mvc.route;

import com.hellokaton.blade.ioc.annotation.Order;
import com.hellokaton.blade.kit.*;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.hook.WebHookOptions;
import com.hellokaton.blade.mvc.http.HttpMethod;
import com.hellokaton.blade.mvc.route.mapping.dynamic.TrieMapping;
import com.hellokaton.blade.mvc.ui.ResponseType;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
    private final List<HookEntry> hooks = new CopyOnWriteArrayList<>();
    private final List<HookEntry> middleware = new CopyOnWriteArrayList<>();
    private final Map<Route, WebHookOptions> hookOptions = new ConcurrentHashMap<>();
    private final Map<String, Method[]> classMethodPool = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> controllerPool = new ConcurrentHashMap<>(8);
    private static final AtomicLong HOOK_SEQ = new AtomicLong();

    private final DynamicMapping routeMapping = new TrieMapping();

    private Route addRoute(HttpMethod httpMethod, String path, RouteHandler handler) throws NoSuchMethodException {
        return addRoute(httpMethod, path, handler, null);
    }

    private Route addRoute(HttpMethod httpMethod, String path, RouteHandler handler, WebHookOptions options) throws NoSuchMethodException {
        Class<?> handleType = handler.getClass();
        Method method = handleType.getMethod(RouteMatcher.METHOD_NAME, RouteContext.class);
        return addRoute(httpMethod, path, handler, RouteHandler.class, method, null, options);
    }

    Route addRoute(Route route) {
        return addRoute(route, null);
    }

    Route addRoute(Route route, WebHookOptions options) {
        String path = route.getPath();
        HttpMethod httpMethod = route.getHttpMethod();
        Object controller = route.getTarget();
        Class<?> controllerType = route.getTargetType();
        Method method = route.getAction();
        ResponseType responseType = route.getResponseType();
        return addRoute(httpMethod, path, controller, controllerType, method, responseType, options);
    }

    private Route addRoute(HttpMethod httpMethod, String path, Object controller,
                           Class<?> controllerType, Method method, ResponseType responseType, WebHookOptions options) {

        String key = path + "#" + httpMethod.name();

        // exist
        if (!BladeKit.isWebHook(httpMethod) && this.routes.containsKey(key)) {
            log.warn("\tRoute {} -> {} has exist", path, httpMethod);
        }

        Route route = new Route(httpMethod, path, controller, controllerType, method, responseType);
        if (BladeKit.isWebHook(httpMethod)) {
            WebHookOptions opt = options == null ? new WebHookOptions() : options;
            Order order = controllerType.getAnnotation(Order.class);
            int p = opt.isPrioritySet() ? opt.getPriority() : (order != null ? order.value() : 0);
            route.setSort(p);
            HookEntry entry = new HookEntry(route, opt, p, HOOK_SEQ.getAndIncrement());
            this.hooks.add(entry);
            this.hookOptions.put(route, opt);
        } else {
            this.routes.put(key, route);
        }
        return route;
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod) {
        return addRoute(path, handler, httpMethod, null);
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod, WebHookOptions options) {
        try {
            return addRoute(httpMethod, path, handler, options);
        } catch (Exception e) {
            log.error("", e);
        }
        return null;
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
                    this.addRoute(httpMethod, path, controller, clazz, method, null, null);
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
        return hooks.stream().anyMatch(entry -> entry.route.getHttpMethod().equals(HttpMethod.BEFORE));
    }

    public boolean hasAfterHook() {
        return hooks.stream().anyMatch(entry -> entry.route.getHttpMethod().equals(HttpMethod.AFTER));
    }

    /**
     * Find all in before of the hook
     *
     * @param path request path
     */
    public List<Route> getBefore(String path) {
        String cleanPath = normalize(path);

        List<HookEntry> list = hooks.stream()
                .filter(entry -> entry.route.getHttpMethod() == HttpMethod.BEFORE)
                .filter(entry -> entry.matchesPath(cleanPath))
                .sorted(HookEntry.COMPARATOR)
                .collect(Collectors.toList());

        List<Route> collect = list.stream().map(entry -> entry.route).collect(Collectors.toList());
        this.giveMatch(path, collect);
        return collect;
    }

    /**
     * Find all in after of the hooks
     *
     * @param path request path
     */
    public List<Route> getAfter(String path) {
        String cleanPath = normalize(path);

        List<HookEntry> afters = hooks.stream()
                .filter(entry -> entry.route.getHttpMethod() == HttpMethod.AFTER)
                .filter(entry -> entry.matchesPath(cleanPath))
                .sorted(HookEntry.COMPARATOR)
                .collect(Collectors.toList());

        List<Route> routes = afters.stream().map(entry -> entry.route).collect(Collectors.toList());
        this.giveMatch(path, routes);
        return routes;
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
     * Matching path
     *
     * @param routePath   route path
     * @param pathToMatch match path
     * @return return match is success
     */
    private static String normalize(String path) {
        path = PathKit.fixPath(path);
        try {
            URI uri = new URI(path);
            path = uri.getPath();
        } catch (URISyntaxException ignored) {
        }
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
        }
        path = path.replaceAll("/+", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

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
        hooks.stream().map(entry -> entry.route).forEach(route -> logAddRoute(log, route));

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

    public void addMiddleware(WebHook webHook, WebHookOptions options) {
        Method method = ReflectKit.getMethod(WebHook.class, HttpMethod.BEFORE.name().toLowerCase(), RouteContext.class);
        Route route = new Route(HttpMethod.BEFORE, "/**", webHook, WebHook.class, method, null);
        WebHookOptions opt = options == null ? new WebHookOptions() : options;
        int p = opt.isPrioritySet() ? opt.getPriority() : 0;
        route.setSort(p);
        HookEntry entry = new HookEntry(route, opt, p, HOOK_SEQ.getAndIncrement());
        this.middleware.add(entry);
        this.hookOptions.put(route, opt);
    }

    public List<Route> getMiddleware() {
        return this.middleware.stream().map(h -> h.route).collect(Collectors.toList());
    }

    public List<Route> getMiddleware(String path) {
        String cleanPath = normalize(path);
        return this.middleware.stream()
                .filter(entry -> entry.matchesPath(cleanPath))
                .sorted(HookEntry.COMPARATOR)
                .map(entry -> entry.route)
                .collect(Collectors.toList());
    }

    public int middlewareCount() {
        return this.middleware.size();
    }

    public WebHookOptions getHookOptions(Route route) {
        return this.hookOptions.get(route);
    }

    static final class HookEntry {
        final Route route;
        final WebHookOptions options;
        final int priority;
        final long order;
        final String routePath;

        HookEntry(Route route, WebHookOptions options, int priority, long order) {
            this.route = route;
            this.options = options;
            this.priority = priority;
            this.order = order;
            String rp = route.getRewritePath();
            if (rp == null) {
                rp = route.getPath();
            }
            this.routePath = normalize(rp);
        }

        boolean matchesPath(String requestPath) {
            try {
                if (!requestPath.startsWith(this.routePath)) {
                    return false;
                }
                return options.matchesPath(requestPath);
            } catch (Exception e) {
                RouteMatcher.log.warn("SelectiveMiddleware: {}", e.getMessage(), e);
                return false;
            }
        }

        static final Comparator<HookEntry> COMPARATOR = (a, b) -> {
            int c = Integer.compare(a.priority, b.priority);
            if (c != 0) {
                return c;
            }
            return Long.compare(a.order, b.order);
        };
    }

}
