package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/**
 * Options for WebHook selective execution.
 */
public class WebHookOptions {

    private static final Logger log = LoggerFactory.getLogger(WebHookOptions.class);

    private final List<String> includes = new ArrayList<>();
    private final List<Pattern> includePatterns = new ArrayList<>();
    private final List<String> excludes = new ArrayList<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();
    private final Set<String> methods = new LinkedHashSet<>();
    private int priority = 0;
    private Predicate<RouteContext> predicate = ctx -> true;

    public WebHookOptions() {
    }

    public WebHookOptions addInclude(String pattern) {
        String norm = normalizePath(pattern);
        includes.add(norm);
        includePatterns.add(compilePattern(norm));
        return this;
    }

    public WebHookOptions addExclude(String pattern) {
        String norm = normalizePath(pattern);
        excludes.add(norm);
        excludePatterns.add(compilePattern(norm));
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (null == methods) return this;
        for (String m : methods) {
            if (null == m) continue;
            this.methods.add(m.toUpperCase(Locale.ROOT));
        }
        return this;
    }

    public WebHookOptions priority(int priority) {
        this.priority = priority;
        return this;
    }

    public WebHookOptions condition(Predicate<RouteContext> predicate) {
        if (null != predicate) {
            this.predicate = predicate;
        }
        return this;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public int getPriority() {
        return priority;
    }

    public Predicate<RouteContext> getPredicate() {
        return predicate;
    }

    boolean matches(String method, String path) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String p = normalizePath(path);

        boolean matched = false;
        if (includePatterns.isEmpty()) {
            matched = true;
        } else {
            for (Pattern pattern : includePatterns) {
                if (pattern.matcher(p).matches()) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) return false;
        for (Pattern pattern : excludePatterns) {
            if (pattern.matcher(p).matches()) {
                return false;
            }
        }
        if (!methods.isEmpty() && !methods.contains(m)) {
            return false;
        }
        return true;
    }

    static String normalizePath(String path) {
        if (null == path || path.isEmpty()) {
            return "/";
        }
        String result;
        try {
            result = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            result = path;
        }
        int q = result.indexOf('?');
        if (q >= 0) result = result.substring(0, q);
        int f = result.indexOf('#');
        if (f >= 0) result = result.substring(0, f);
        result = result.replaceAll("/+", "/");
        if (!result.startsWith("/")) result = "/" + result;
        if (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.toLowerCase(Locale.ROOT);
    }

    private static Pattern compilePattern(String pattern) {
        StringBuilder regex = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            switch (c) {
                case '\\':
                    escaping = true;
                    break;
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (escaping) {
            log.warn("SelectiveMiddleware: Dangling escape in pattern: {}", pattern);
            regex.append("\\\\");
        }
        return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebHookOptions)) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority &&
                Objects.equals(includes, that.includes) &&
                Objects.equals(excludes, that.excludes) &&
                Objects.equals(methods, that.methods) &&
                Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includes, excludes, methods, priority, predicate);
    }
}

