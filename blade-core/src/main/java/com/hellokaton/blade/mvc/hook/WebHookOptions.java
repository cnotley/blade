package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for {@link WebHook} registrations.
 */
@Slf4j
@NoArgsConstructor
public class WebHookOptions {

    private final List<Pattern> includePatterns = new ArrayList<>();
    private final List<String> includeSources = new ArrayList<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();
    private final List<String> excludeSources = new ArrayList<>();
    private final Set<String> methods = new LinkedHashSet<>();
    @Getter
    private int priority = 0;
    @Getter
    private Predicate<RouteContext> predicate = ctx -> true;

    public WebHookOptions addInclude(String pattern) {
        addPattern(pattern, includePatterns, includeSources);
        return this;
    }

    public WebHookOptions addExclude(String pattern) {
        addPattern(pattern, excludePatterns, excludeSources);
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (methods != null) {
            for (String m : methods) {
                if (m != null) {
                    this.methods.add(m.toUpperCase(Locale.ROOT));
                }
            }
        }
        return this;
    }

    public WebHookOptions priority(int priority) {
        this.priority = priority;
        return this;
    }

    public WebHookOptions condition(Predicate<RouteContext> predicate) {
        this.predicate = predicate == null ? ctx -> true : predicate;
        return this;
    }

    public List<Pattern> getIncludes() {
        return includePatterns;
    }

    public List<Pattern> getExcludes() {
        return excludePatterns;
    }

    public Set<String> getMethods() {
        return methods;
    }

    private void addPattern(String pattern, List<Pattern> patterns, List<String> sources) {
        if (pattern == null) {
            return;
        }
        String normalized = normalizePath(pattern);
        Pattern p = compileGlob(normalized, pattern);
        patterns.add(p);
        sources.add(normalized);
    }

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
        } catch (Exception e) {
            // ignore
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

    private Pattern compileGlob(String normalized, String original) {
        StringBuilder regex = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (escaping) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
            } else {
                if (c == '\\') {
                    escaping = true;
                } else if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append("[^/]");
                } else {
                    if (".[]{}()+-^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        if (escaping) {
            log.warn("SelectiveMiddleware: Dangling escape in pattern '{}'", original);
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
                includeSources.equals(that.includeSources) &&
                excludeSources.equals(that.excludeSources) &&
                methods.equals(that.methods) &&
                Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includeSources, excludeSources, methods, priority, predicate);
    }
}

