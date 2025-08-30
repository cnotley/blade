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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for {@link WebHook} execution.
 */
@Slf4j
public class WebHookOptions {

    private final Set<String> includes = new LinkedHashSet<>();
    private final Set<String> excludes = new LinkedHashSet<>();
    private final EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
    @Getter
    private int priority = 0;
    @Getter
    private long order = 0L;
    private Predicate<RouteContext> predicate;

    private final List<Pattern> includePatterns = new CopyOnWriteArrayList<>();
    private final List<Pattern> excludePatterns = new CopyOnWriteArrayList<>();

    public WebHookOptions() {
    }

    public WebHookOptions addIncludes(String... patterns) {
        if (patterns == null) return this;
        for (String pattern : patterns) {
            String p = canonicalize(pattern);
            if (p == null || includes.contains(p)) {
                continue;
            }
            includes.add(p);
            includePatterns.add(globToPattern(p));
        }
        return this;
    }

    public WebHookOptions addExcludes(String... patterns) {
        if (patterns == null) return this;
        for (String pattern : patterns) {
            String p = canonicalize(pattern);
            if (p == null || excludes.contains(p)) {
                continue;
            }
            excludes.add(p);
            excludePatterns.add(globToPattern(p));
        }
        return this;
    }

    public WebHookOptions addMethods(HttpMethod... httpMethods) {
        if (httpMethods == null) return this;
        for (HttpMethod m : httpMethods) {
            if (m != null) {
                methods.add(m);
            }
        }
        return this;
    }

    public WebHookOptions addMethods(String... methodNames) {
        if (methodNames == null) return this;
        for (String m : methodNames) {
            if (m == null) continue;
            try {
                methods.add(HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        return this;
    }

    public WebHookOptions priority(int priority) {
        this.priority = priority;
        return this;
    }

    public WebHookOptions predicate(Predicate<RouteContext> predicate) {
        this.predicate = predicate;
        return this;
    }

    public WebHookOptions setOrder(long order) {
        this.order = order;
        return this;
    }

    public Predicate<RouteContext> getPredicate() {
        return predicate;
    }

    public EnumSet<HttpMethod> getMethods() {
        return methods.clone();
    }

    public List<String> getIncludes() {
        return new ArrayList<>(includes);
    }

    public List<String> getExcludes() {
        return new ArrayList<>(excludes);
    }

    public boolean matchesPath(String path) {
        String check = path.toLowerCase(Locale.ROOT);
        if (!includePatterns.isEmpty()) {
            boolean ok = false;
            for (Pattern p : includePatterns) {
                if (p.matcher(check).matches()) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        if (!excludePatterns.isEmpty()) {
            for (Pattern p : excludePatterns) {
                if (p.matcher(check).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean matchesMethod(HttpMethod method) {
        return methods.isEmpty() || methods.contains(method);
    }

    public boolean testPredicate(RouteContext ctx) {
        try {
            return predicate == null || predicate.test(ctx);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: predicate evaluation error", e);
            return false;
        }
    }

    private String canonicalize(String pattern) {
        if (pattern == null) return null;
        String p = pattern.trim();
        if (p.isEmpty()) return null;
        try {
            String fixed = PathKit.fixPath(p);
            fixed = URLDecoder.decode(fixed, StandardCharsets.UTF_8);
            fixed = fixed.replaceAll("/+", "/");
            if (fixed.length() > 1 && fixed.endsWith("/")) {
                fixed = fixed.substring(0, fixed.length() - 1);
            }
            return fixed.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: pattern canonicalization error", e);
            return p.toLowerCase(Locale.ROOT);
        }
    }

    private Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        boolean escaping = false;
        boolean warn = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (escaping) {
                if (c == '*' || c == '?' || c == '\\') {
                    sb.append(Pattern.quote(String.valueOf(c)));
                } else {
                    sb.append("\\\\").append(c);
                }
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
                    if (i == glob.length() - 1) {
                        warn = true;
                    } else {
                        escaping = true;
                    }
                    break;
                default:
                    if (".()|+^${}[]".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        sb.append('$');
        Pattern pattern;
        String regex = sb.toString();
        try {
            pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            warn = true;
            pattern = Pattern.compile(Pattern.quote(glob), Pattern.CASE_INSENSITIVE);
        }
        if (warn) {
            log.warn("SelectiveMiddleware: invalid pattern '{}', treated as literal", glob);
            pattern = Pattern.compile(Pattern.quote(glob), Pattern.CASE_INSENSITIVE);
        }
        return pattern;
    }

    public static String normalizePath(String uri) {
        if (uri == null) return "/";
        try {
            URI u = new URI(uri);
            String path = u.getPath();
            path = PathKit.fixPath(path);
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
            path = path.replaceAll("/+", "/");
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: normalize path error", e);
            String p = PathKit.fixPath(uri);
            p = p.replaceAll("/+", "/");
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.toLowerCase(Locale.ROOT);
        }
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

