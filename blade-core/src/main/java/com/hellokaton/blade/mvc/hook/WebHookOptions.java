package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for selective middleware invocation.
 */
@Slf4j
public class WebHookOptions {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final List<String> includes = new ArrayList<>();
    private final List<String> excludes = new ArrayList<>();
    private final Set<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);

    @Getter
    private int priority = 0;
    @Getter
    private Predicate<RouteContext> predicate;
    @Getter
    private boolean secureMode = false;
    @Getter
    private long order = 0L;

    public WebHookOptions() {
    }

    public WebHookOptions addIncludes(String... patterns) {
        addPattern(this.includes, patterns);
        return this;
    }

    public WebHookOptions addExcludes(String... patterns) {
        addPattern(this.excludes, patterns);
        return this;
    }

    private void addPattern(List<String> list, String... patterns) {
        if (null == patterns) return;
        for (String p : patterns) {
            if (null == p) continue;
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (secureMode && containsTraversal(s)) {
                log.warn("SelectiveMiddleware: insecure pattern rejected - {}", s);
                continue;
            }
            list.add(s);
        }
    }

    public WebHookOptions addMethods(HttpMethod... m) {
        if (m != null) {
            for (HttpMethod method : m) {
                if (method != null) {
                    methods.add(method);
                }
            }
        }
        return this;
    }

    public WebHookOptions addMethods(String... m) {
        if (m != null) {
            for (String method : m) {
                if (null == method) continue;
                String s = method.trim();
                if (s.isEmpty()) continue;
                try {
                    methods.add(HttpMethod.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignore) {
                    // discard unrecognized
                }
            }
        }
        return this;
    }

    public WebHookOptions priority(int p) {
        this.priority = p;
        return this;
    }

    public WebHookOptions predicate(Predicate<RouteContext> predicate) {
        this.predicate = predicate;
        return this;
    }

    public WebHookOptions secureMode(boolean flag) {
        this.secureMode = flag;
        if (flag) {
            validatePatterns(includes);
            validatePatterns(excludes);
        }
        return this;
    }

    private void validatePatterns(List<String> patterns) {
        if (patterns == null) return;
        patterns.removeIf(p -> {
            if (containsTraversal(p)) {
                log.warn("SelectiveMiddleware: insecure pattern rejected - {}", p);
                return true;
            }
            return false;
        });
    }

    private boolean containsTraversal(String pattern) {
        return pattern.contains("..") || pattern.contains(":");
    }

    public boolean matchPath(String path) {
        if (!matches(includes, path, true)) {
            return false;
        }
        if (matches(excludes, path, false)) {
            return false;
        }
        return true;
    }

    private boolean matches(List<String> patterns, String path, boolean empty) {
        if (patterns == null || patterns.isEmpty()) {
            return empty;
        }
        for (String p : patterns) {
            try {
                if (globMatches(p, path)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("SelectiveMiddleware: pattern error - {}", p, e);
                if (p.equalsIgnoreCase(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean match(String path, HttpMethod method, RouteContext ctx) {
        if (!matchPath(path)) {
            return false;
        }
        if (secureMode && path.contains("..")) {
            log.warn("SelectiveMiddleware: insecure path detected - {}", path);
            return false;
        }
        if (!methods.isEmpty() && method != null && !methods.contains(method)) {
            return false;
        }
        if (predicate != null) {
            try {
                if (!predicate.test(ctx)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("SelectiveMiddleware: predicate error", e);
                return false;
            }
        }
        return true;
    }

    private boolean globMatches(String pattern, String path) {
        Pattern regex = globToRegex(pattern);
        return regex.matcher(path).matches();
    }

    private Pattern globToRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        char[] chars = pattern.toCharArray();
        boolean escaping = false;
        boolean inClass = false;
        sb.append('^');
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (escaping) {
                sb.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            switch (c) {
                case '\\':
                    escaping = true;
                    break;
                case '*':
                    if (i + 1 < chars.length && chars[i + 1] == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append(".*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '[':
                    sb.append('[');
                    inClass = true;
                    break;
                case ']':
                    sb.append(']');
                    inClass = false;
                    break;
                default:
                    if (!inClass && ".()+|^$".indexOf(c) != -1) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public void assignOrder() {
        this.order = COUNTER.incrementAndGet();
    }

    public Set<HttpMethod> getMethods() {
        return methods;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public static String normalize(String path) {
        if (path == null) return "/";
        try {
            URI uri = new URI(path);
            path = uri.getPath();
        } catch (Exception e) {
            // ignore
        }
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignore) {
        }
        path = PathKit.fixPath(path);
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }
}
