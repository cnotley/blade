package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Options to control when a {@link WebHook} should run.
 * <p>
 * Instances are mutable and provide a fluent API.  Once a hook is registered
 * the framework will snapshot the instance and assign a registration order.
 */
@Slf4j
public class WebHookOptions {

    private final List<String> includeStrings = new ArrayList<>();
    private final List<Pattern> includePatterns = new ArrayList<>();
    private final List<String> excludeStrings = new ArrayList<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();
    private final EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);

    @Getter
    private int priority = 0;

    private Predicate<RouteContext> predicate;

    @Getter
    private long order;

    public WebHookOptions() {
    }

    /** Add an include glob pattern. */
    public WebHookOptions include(String pattern) {
        if (pattern == null) {
            return this;
        }
        String p = normalizePattern(pattern);
        includeStrings.add(p);
        includePatterns.add(toPattern(p));
        return this;
    }

    /** Add an exclude glob pattern. */
    public WebHookOptions exclude(String pattern) {
        if (pattern == null) {
            return this;
        }
        String p = normalizePattern(pattern);
        excludeStrings.add(p);
        excludePatterns.add(toPattern(p));
        return this;
    }

    /** Constrain activation to specific HTTP methods. */
    public WebHookOptions methods(HttpMethod... ms) {
        if (ms != null) {
            for (HttpMethod m : ms) {
                if (m != null) {
                    methods.add(m);
                }
            }
        }
        return this;
    }

    /** Set priority; lower values run earlier. */
    public WebHookOptions priority(int p) {
        this.priority = p;
        return this;
    }

    /** Optional runtime predicate. */
    public WebHookOptions predicate(Predicate<RouteContext> pred) {
        this.predicate = pred;
        return this;
    }

    void setOrder(long o) {
        this.order = o;
    }

    public Predicate<RouteContext> getPredicate() {
        return predicate;
    }

    /**
     * Evaluate all conditions against the given context.
     */
    public boolean test(RouteContext ctx) {
        try {
            String path = normalizePath(ctx.uri());
            if (!matchesPath(path)) {
                return false;
            }
            if (!methods.isEmpty()) {
                try {
                    HttpMethod m = HttpMethod.valueOf(ctx.method().toUpperCase(Locale.ROOT));
                    if (!methods.contains(m)) {
                        return false;
                    }
                } catch (IllegalArgumentException e) {
                    return false; // unrecognized verb
                }
            }
            return predicate == null || predicate.test(ctx);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: exception during match", e);
            return false;
        }
    }

    /** Only evaluate include/exclude patterns. */
    public boolean matchesPath(String normalizedPath) {
        if (!includePatterns.isEmpty()) {
            boolean ok = false;
            for (Pattern p : includePatterns) {
                if (p.matcher(normalizedPath).matches()) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        for (Pattern p : excludePatterns) {
            if (p.matcher(normalizedPath).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizePattern(String pattern) {
        return normalizePath(pattern);
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return "/";
        }
        path = PathKit.fixPath(path);
        try {
            URI uri = new URI(path);
            path = uri.getPath();
        } catch (URISyntaxException e) {
            // ignore and use raw path
        }
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        path = PathKit.cleanPath(path);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    private Pattern toPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (escape) {
                regex.append(Pattern.quote(Character.toString(c)));
                escape = false;
            } else {
                switch (c) {
                    case '*':
                        regex.append(".*");
                        break;
                    case '?':
                        regex.append("[^/]");
                        break;
                    case '\\':
                        escape = true;
                        break;
                    default:
                        regex.append(Pattern.quote(Character.toString(c)));
                }
            }
        }
        if (escape) {
            regex.append("\\\\");
        }
        String r = "^" + regex + "$";
        try {
            return Pattern.compile(r, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            log.warn("SelectiveMiddleware: bad pattern {}", glob);
            return Pattern.compile("^" + Pattern.quote(glob) + "$", Pattern.CASE_INSENSITIVE);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebHookOptions)) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority &&
                includeStrings.equals(that.includeStrings) &&
                excludeStrings.equals(that.excludeStrings) &&
                methods.equals(that.methods) &&
                Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includeStrings, excludeStrings, methods, priority, predicate);
    }
}
