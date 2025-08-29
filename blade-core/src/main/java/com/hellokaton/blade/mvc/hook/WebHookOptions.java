package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for {@link WebHook} registration allowing selective invocation.
 */
public class WebHookOptions {

    private static final Logger log = LoggerFactory.getLogger(WebHookOptions.class);

    private final List<String> includeSources = new ArrayList<>();
    private final List<Pattern> includePatterns = new ArrayList<>();

    private final List<String> excludeSources = new ArrayList<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();

    private final Set<String> methods = new LinkedHashSet<>();

    private int priority;
    private Predicate<RouteContext> condition = ctx -> true;

    public WebHookOptions() {
    }

    public WebHookOptions addInclude(String pattern) {
        String norm = normalize(pattern);
        if (includeSources.contains(norm)) {
            return this;
        }
        includeSources.add(norm);
        includePatterns.add(compileGlob(norm));
        return this;
    }

    public WebHookOptions addExclude(String pattern) {
        String norm = normalize(pattern);
        if (excludeSources.contains(norm)) {
            return this;
        }
        excludeSources.add(norm);
        excludePatterns.add(compileGlob(norm));
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (null == methods) {
            return this;
        }
        for (String method : methods) {
            if (null == method) {
                continue;
            }
            this.methods.add(method.toUpperCase(Locale.ROOT));
        }
        return this;
    }

    public WebHookOptions priority(int priority) {
        this.priority = priority;
        return this;
    }

    public WebHookOptions condition(Predicate<RouteContext> predicate) {
        if (null != predicate) {
            this.condition = predicate;
        }
        return this;
    }

    public List<Pattern> getIncludePatterns() {
        return includePatterns;
    }

    public List<Pattern> getExcludePatterns() {
        return excludePatterns;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public int getPriority() {
        return priority;
    }

    public Predicate<RouteContext> getCondition() {
        return condition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority &&
                Objects.equals(includeSources, that.includeSources) &&
                Objects.equals(excludeSources, that.excludeSources) &&
                Objects.equals(methods, that.methods) &&
                Objects.equals(condition, that.condition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includeSources, excludeSources, methods, priority, condition);
    }

    /**
     * Normalize a request or pattern path.
     */
    public static String normalize(String path) {
        if (path == null) {
            return "/";
        }
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignore) {
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int f = path.indexOf('#');
        if (f >= 0) {
            path = path.substring(0, f);
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

    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            if (c == '\\') {
                if (i == pattern.length() - 1) {
                    log.warn("SelectiveMiddleware: dangling escape in pattern {}", pattern);
                    regex.append("\\\\");
                } else {
                    escaping = true;
                }
                continue;
            }
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}

