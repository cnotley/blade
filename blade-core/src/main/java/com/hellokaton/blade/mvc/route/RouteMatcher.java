package com.hellokaton.blade.mvc.route;

import com.hellokaton.blade.ioc.annotation.Order;
import com.hellokaton.blade.kit.*;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.hook.WebHookOptions;
import com.hellokaton.blade.mvc.hook.GlobMatch;
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
    // registries for before/after hooks and global middleware
    private final List<HookEntry> beforeHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<HookEntry> afterHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<HookEntry> middlewareEntries = new java.util.concurrent.CopyOnWriteArrayList<>();
    // track duplicates across middleware and before/after hooks
    private final java.util.concurrent.ConcurrentHashMap<Object, java.util.Set<WebHookOptions>> duplicates = new java.util.concurrent.ConcurrentHashMap<>();
    // global registration counter
    private static final java.util.concurrent.atomic.AtomicLong registrationSeq = new java.util.concurrent.atomic.AtomicLong();
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
            // route-level hook registration uses beforeHooks/afterHooks lists instead of hooks map
            Order order = controllerType.getAnnotation(Order.class);
            if (null != order) {
                route.setSort(order.value());
            }
            // build default options for legacy before/after registration
            WebHookOptions options = new WebHookOptions();
            options.addIncludes(path);
            options.setRegistrationOrder(registrationSeq.incrementAndGet());
            HookEntry entry = new HookEntry(route, options);
            if (httpMethod == HttpMethod.BEFORE) {
                beforeHooks.add(entry);
            } else {
                afterHooks.add(entry);
            }
        } else {
            this.routes.put(key, route);
        }
        return route;
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod) {
        try {
            return addRoute(httpMethod, path, handler);
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
        return !beforeHooks.isEmpty();
    }

    public boolean hasAfterHook() {
        return !afterHooks.isEmpty();
    }

    /**
     * Find all in before of the hook
     *
     * @param path request path
     */
    public List<Route> getBefore(String path) {
        return getBeforeEntries(path).stream()
                .map(HookEntry::getRoute)
                .collect(Collectors.toList());
    }

    /**
     * Returns before hook entries with static path criteria matching the given request path.
     * Includes/excludes are evaluated but method and predicate filters are deferred to runtime.
     */
    public List<HookEntry> getBeforeEntries(String path) {
        String normalized = normalizePath(path);
        List<HookEntry> filtered = beforeHooks.stream()
                .filter(entry -> staticMatch(entry.getOptions(), normalized))
                .sorted(Comparator.comparingInt((HookEntry e) -> e.getOptions().getPriority())
                        .thenComparingLong(e -> e.getOptions().getRegistrationOrder()))
                .collect(Collectors.toList());
        return filtered;
    }

    /**
     * Find all in after of the hooks
     *
     * @param path request path
     */
    public List<Route> getAfter(String path) {
        return getAfterEntries(path).stream()
                .map(HookEntry::getRoute)
                .collect(Collectors.toList());
    }

    public List<HookEntry> getAfterEntries(String path) {
        String normalized = normalizePath(path);
        return afterHooks.stream()
                .filter(entry -> staticMatch(entry.getOptions(), normalized))
                .sorted(Comparator.comparingInt((HookEntry e) -> e.getOptions().getPriority())
                        .thenComparingLong(e -> e.getOptions().getRegistrationOrder()))
                .collect(Collectors.toList());
    }

    public List<Route> getMiddleware() {
        return middlewareEntries.stream().map(HookEntry::getRoute).collect(Collectors.toList());
    }

    public List<HookEntry> getMiddlewareEntries() {
        return middlewareEntries;
    }

    public int middlewareCount() {
        return middlewareEntries.size();
    }

    /**
     * Sort of path
     *
     * @param uri    request uri
     * @param routes route list
     */
    private void giveMatch(final String uri, List<Route> routes) {
        // legacy no-op; static selection now handled by extended glob semantics
    }

    /**
     * Matching path
     *
     * @param routePath   route path
     * @param pathToMatch match path
     * @return return match is success
     */
    private boolean matchesPath(String routePath, String pathToMatch) {
        return pathToMatch.startsWith(routePath);
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

    private String normalizePath(String rawPath) {
        String fixed = PathKit.fixPath(rawPath);
        try {
            URI uri = new URI(fixed);
            String p = uri.getPath();
            if (p == null) {
                p = fixed;
            }
            // use old-style decode signature for compatibility with JDK 8
            p = java.net.URLDecoder.decode(p, "UTF-8");
            // collapse sequences of slashes
            p = p.replaceAll("/+", "/");
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return fixed.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private boolean staticMatch(WebHookOptions options, String normalizedPath) {
        // includes: if empty match-all
        boolean matched = true;
        if (options.getIncludes() != null && !options.getIncludes().isEmpty()) {
            matched = false;
            for (String pattern : options.getIncludes()) {
                if (options.isSecureMode() && isInsecurePattern(pattern)) {
                    log.warn("SelectiveMiddleware: insecure include pattern '{}'", pattern);
                    continue;
                }
                GlobMatch gm = GlobMatch.compile(pattern, true);
                if (gm.matches(normalizedPath)) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            return false;
        }
        if (options.getExcludes() != null && !options.getExcludes().isEmpty()) {
            for (String pattern : options.getExcludes()) {
                if (options.isSecureMode() && isInsecurePattern(pattern)) {
                    log.warn("SelectiveMiddleware: insecure exclude pattern '{}'", pattern);
                    continue;
                }
                GlobMatch gm = GlobMatch.compile(pattern, true);
                if (gm.matches(normalizedPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isInsecurePattern(String pattern) {
        return pattern.contains("..") || pattern.matches("^[a-zA-Z]:\\\\.*");
    }

    /**
     * register route to container
     */
    public void register() {
        routes.values().forEach(route -> logAddRoute(log, route));
        beforeHooks.stream().map(HookEntry::getRoute).forEach(route -> logAddRoute(log, route));
        afterHooks.stream().map(HookEntry::getRoute).forEach(route -> logAddRoute(log, route));
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
        addMiddleware(webHook, new WebHookOptions());
    }

    public void addMiddleware(WebHook webHook, WebHookOptions options) {
        if (options == null) {
            options = new WebHookOptions();
        }
        // assign default include for legacy match-all to behave globally
        if (options.getIncludes().isEmpty()) {
            // no includes indicates match-all
        }
        options.setRegistrationOrder(registrationSeq.incrementAndGet());
        // check duplicate registrations
        if (isDuplicate(webHook, options)) {
            return;
        }
        Method method = ReflectKit.getMethod(WebHook.class, HttpMethod.BEFORE.name().toLowerCase(), RouteContext.class);
        Route route = new Route(HttpMethod.BEFORE, "/**", webHook, WebHook.class, method, null);
        middlewareEntries.add(new HookEntry(route, options));
    }

    /**
     * Add route-level before/after hook with options.
     */
    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod, WebHookOptions options) {
        try {
            Route route = addRoute(httpMethod, path, handler);
            if (BladeKit.isWebHook(httpMethod)) {
                // adjust the previously-constructed HookEntry in the before/after list if needed
                List<HookEntry> list = (httpMethod == HttpMethod.BEFORE) ? beforeHooks : afterHooks;
                HookEntry entry = list.get(list.size() - 1);
                WebHookOptions opts = options != null ? options : new WebHookOptions();
                if (opts.getIncludes().isEmpty()) {
                    opts.addIncludes(path);
                }
                opts.setRegistrationOrder(registrationSeq.incrementAndGet());
                if (isDuplicate(handler, opts)) {
                    list.remove(entry);
                    return route;
                }
                entry.setOptions(opts);
            }
            return route;
        } catch (Exception e) {
            log.error("", e);
        }
        return null;
    }

    private boolean isDuplicate(Object key, WebHookOptions options) {
        java.util.Set<WebHookOptions> set = duplicates.computeIfAbsent(key, k -> java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>())) ;
        if (set.stream().anyMatch(opts -> opts.equals(options))) {
            return true;
        }
        set.add(options);
        return false;
    }

}
