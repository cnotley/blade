package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
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
 * Options for selective middleware and hooks.
 */
@Slf4j
public class WebHookOptions {

    private static final AtomicLong ORDER_COUNTER = new AtomicLong();

    @Getter
    private final Set<String> includes = new LinkedHashSet<>();
    private final List<Pattern> includePatterns = new ArrayList<>();
    @Getter
    private final Set<String> excludes = new LinkedHashSet<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();
    @Getter
    private final EnumSet<com.hellokaton.blade.mvc.http.HttpMethod> methods = EnumSet.noneOf(com.hellokaton.blade.mvc.http.HttpMethod.class);
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
        addPattern(this.includes, this.includePatterns, patterns);
        return this;
    }

    public WebHookOptions addExcludes(String... patterns) {
        addPattern(this.excludes, this.excludePatterns, patterns);
        return this;
    }

    public WebHookOptions addMethods(com.hellokaton.blade.mvc.http.HttpMethod... mds) {
        if (null == mds) return this;
        for (com.hellokaton.blade.mvc.http.HttpMethod m : mds) {
            if (null != m) {
                this.methods.add(m);
            }
        }
        return this;
    }

    public WebHookOptions addMethods(String... methodNames) {
        if (null == methodNames) return this;
        for (String m : methodNames) {
            if (null == m) continue;
            String mm = m.trim();
            if (mm.isEmpty()) continue;
            try {
                this.methods.add(com.hellokaton.blade.mvc.http.HttpMethod.valueOf(mm.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
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

    public WebHookOptions secureMode(boolean secure) {
        this.secureMode = secure;
        return this;
    }

    public WebHookOptions setOrder(long order) {
        this.order = order;
        return this;
    }

    public boolean testPath(String path) {
        boolean include = this.includes.isEmpty();
        if (!include) {
            for (Pattern p : includePatterns) {
                if (p.matcher(path).matches()) {
                    include = true;
                    break;
                }
            }
        }
        if (!include) return false;
        for (Pattern p : excludePatterns) {
            if (p.matcher(path).matches()) {
                return false;
            }
        }
        return true;
    }

    public boolean test(RouteContext context, String path, com.hellokaton.blade.mvc.http.HttpMethod method) {
        if (!testPath(path)) {
            return false;
        }
        return testDynamic(context, path, method);
    }

    public boolean testDynamic(RouteContext context, String path, com.hellokaton.blade.mvc.http.HttpMethod method) {
        try {
            if (this.secureMode && !isSecurePath(path)) {
                log.warn("SelectiveMiddleware: insecure path {}", path);
                return false;
            }
            if (!methods.isEmpty() && !methods.contains(method)) {
                return false;
            }
            if (null != predicate) {
                if (!predicate.test(context)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: option evaluation error", e);
            return false;
        }
    }

    private void addPattern(Set<String> holder, List<Pattern> compiled, String... patterns) {
        if (null == patterns) return;
        for (String pattern : patterns) {
            if (null == pattern) continue;
            String p = pattern.trim();
            if (p.isEmpty()) continue;
            holder.add(p);
            String lp = p.toLowerCase(Locale.ROOT);
            if (secureMode && (p.contains(".."))) {
                log.warn("SelectiveMiddleware: insecure pattern {}", p);
                compiled.add(Pattern.compile(Pattern.quote(lp), Pattern.CASE_INSENSITIVE));
            } else {
                compiled.add(compileGlob(p));
            }
        }
    }

    private Pattern compileGlob(String pattern) {
        String glob = pattern.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        int inClass = 0;
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (escape) {
                sb.append(Pattern.quote(String.valueOf(ch)));
                escape = false;
                continue;
            }
            switch (ch) {
                case '\\':
                    escape = true;
                    break;
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append(".*");
                    }
                    break;
                case '?':
                    sb.append('.');
                    break;
                case '[':
                    inClass++;
                    sb.append('[');
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '!') {
                        sb.append('^');
                        i++;
                    }
                    break;
                case ']':
                    if (inClass > 0) {
                        inClass--;
                        sb.append(']');
                    } else {
                        sb.append("\\]");
                    }
                    break;
                default:
                    if (".()|+^$".indexOf(ch) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(ch);
            }
        }
        if (escape) {
            log.warn("SelectiveMiddleware: dangling escape in pattern {}", pattern);
            return Pattern.compile(Pattern.quote(glob));
        }
        try {
            return Pattern.compile("^" + sb + "$", Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: malformed pattern {}", pattern, e);
            return Pattern.compile(Pattern.quote(glob), Pattern.CASE_INSENSITIVE);
        }
    }

    private boolean isSecurePath(String path) {
        return !path.contains("..");
    }

    public static String normalizePath(String raw) {
        if (null == raw) return "/";
        try {
            String fixed = PathKit.fixPath(raw);
            URI uri = new URI(fixed);
            String p = uri.getPath();
            p = PathKit.fixPath(p);
            String decoded = URLDecoder.decode(p, StandardCharsets.UTF_8.name());
            decoded = decoded.replaceAll("/+", "/");
            if (decoded.length() > 1 && decoded.endsWith("/")) {
                decoded = decoded.substring(0, decoded.length() - 1);
            }
            return decoded.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            String p = PathKit.fixPath(raw).replaceAll("/+", "/");
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority && secureMode == that.secureMode && Objects.equals(includes, that.includes) && Objects.equals(excludes, that.excludes) && Objects.equals(methods, that.methods) && Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includes, excludes, methods, priority, predicate, secureMode);
    }

    public static WebHookOptions defaultOptions() {
        WebHookOptions options = new WebHookOptions();
        options.setOrder(ORDER_COUNTER.incrementAndGet());
        return options;
    }

    public WebHookOptions prepare() {
        if (this.order == 0L) {
            this.order = ORDER_COUNTER.incrementAndGet();
        }
        return this;
    }
}

