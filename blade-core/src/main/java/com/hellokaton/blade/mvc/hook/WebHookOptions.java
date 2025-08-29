package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Options for {@link WebHook} selective invocation.
 */
@Getter
public class WebHookOptions {

    private final List<String> includePatterns = new CopyOnWriteArrayList<>();
    private final List<Pattern> includeRegex = new CopyOnWriteArrayList<>();

    private final List<String> excludePatterns = new CopyOnWriteArrayList<>();
    private final List<Pattern> excludeRegex = new CopyOnWriteArrayList<>();

    private final Set<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);

    private int priority = 0;

    private Predicate<RouteContext> condition = ctx -> true;

    public WebHookOptions() {
        // default include all paths
        this.includePatterns.add("/");
        this.includeRegex.add(Pattern.compile(".*"));
    }

    public WebHookOptions addInclude(String pattern) {
        String p = normalizePattern(pattern);
        if (!includePatterns.contains(p)) {
            includePatterns.add(p);
            includeRegex.add(globToPattern(p));
        }
        return this;
    }

    public WebHookOptions addExclude(String pattern) {
        String p = normalizePattern(pattern);
        if (!excludePatterns.contains(p)) {
            excludePatterns.add(p);
            excludeRegex.add(globToPattern(p));
        }
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (methods == null) {
            return this;
        }
        for (String m : methods) {
            if (m == null) continue;
            try {
                HttpMethod httpMethod = HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT));
                this.methods.add(httpMethod);
            } catch (Exception ignore) {
                // ignore unknown methods
            }
        }
        return this;
    }

    public WebHookOptions priority(int priority) {
        this.priority = priority;
        return this;
    }

    public WebHookOptions condition(Predicate<RouteContext> predicate) {
        if (predicate != null) {
            this.condition = predicate;
        }
        return this;
    }

    // ---------------------- matching helpers ----------------------

    public boolean matches(String path, HttpMethod method, RouteContext context) {
        String clean = normalizePath(path);
        // includes
        boolean included = includeRegex.isEmpty();
        for (Pattern p : includeRegex) {
            try {
                if (p.matcher(clean).matches()) {
                    included = true;
                    break;
                }
            } catch (Exception e) {
                // ignore bad pattern
            }
        }
        if (!included) {
            return false;
        }
        for (Pattern p : excludeRegex) {
            try {
                if (p.matcher(clean).matches()) {
                    return false;
                }
            } catch (Exception e) {
                // ignore bad pattern
            }
        }
        if (!methods.isEmpty() && method != null && !methods.contains(method)) {
            return false;
        }
        try {
            return condition.test(context);
        } catch (Exception e) {
            return false;
        }
    }

    // normalize incoming path for comparison
    public static String normalizePath(String path) {
        if (path == null) {
            return "/";
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int f = path.indexOf('#');
        if (f >= 0) {
            path = path.substring(0, f);
        }
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignore) {
        }
        path = path.replaceAll("/+", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    private static String normalizePattern(String pattern) {
        if (pattern == null) {
            return "/";
        }
        return normalizePath(pattern);
    }

    private static Pattern globToPattern(String pattern) {
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                sb.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '\\':
                    escaping = true;
                    break;
                default:
                    sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (escaping) {
            // dangling escape
            sb.append(Pattern.quote("\\"));
        }
        return Pattern.compile("^" + sb + "(/)?$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebHookOptions)) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority &&
                Objects.equals(includePatterns, that.includePatterns) &&
                Objects.equals(excludePatterns, that.excludePatterns) &&
                Objects.equals(methods, that.methods) &&
                Objects.equals(condition, that.condition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includePatterns, excludePatterns, methods, priority, condition);
    }
}
