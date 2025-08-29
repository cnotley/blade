package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Holder for a {@link WebHook} along with its matching rules.
 */
@Getter
@Slf4j
public class Middleware {

    private static final String LOG_PREFIX = "[SelectiveMiddleware]";

    private final WebHook hook;
    private final List<Pattern> include;
    private final List<Pattern> exclude;
    private final Set<String> methods;
    private final int priority;
    private final Predicate<RouteContext> condition;
    private final long order;

    // for duplicate detection
    private final Set<String> includeStrings;
    private final Set<String> excludeStrings;

    Middleware(WebHook hook, WebHookRule rule, long order) {
        this.hook = hook;
        this.includeStrings = normalizePatterns(rule.getInclude());
        this.excludeStrings = normalizePatterns(rule.getExclude());
        this.include = compile(this.includeStrings);
        this.exclude = compile(this.excludeStrings);
        this.methods = rule.getMethods();
        this.priority = rule.getPriority();
        this.condition = rule.getCondition();
        this.order = order;
    }

    /** Normalize and percent-decode a path or pattern. */
    static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String result;
        try {
            result = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            result = path;
        }
        int q = result.indexOf('?');
        if (q >= 0) {
            result = result.substring(0, q);
        }
        int f = result.indexOf('#');
        if (f >= 0) {
            result = result.substring(0, f);
        }
        result = result.replaceAll("/+", "/");
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizePatterns(List<String> patterns) {
        Set<String> set = new LinkedHashSet<>();
        for (String p : patterns) {
            if (p != null) {
                set.add(normalize(p));
            }
        }
        return set;
    }

    private static List<Pattern> compile(Set<String> patterns) {
        List<Pattern> list = new ArrayList<>();
        for (String p : patterns) {
            list.add(globToRegex(p));
        }
        return list;
    }

    private static Pattern globToRegex(String pattern) {
        if ("/".equals(pattern)) {
            return Pattern.compile("^.*$", Pattern.CASE_INSENSITIVE);
        }
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                sb.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            if (c == '\\') {
                if (i == pattern.length() - 1) {
                    log.warn("{} Malformed pattern '{}'", LOG_PREFIX, pattern);
                    sb.append("\\\\");
                } else {
                    escaping = true;
                }
            } else if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        String regex = "^" + sb + "/?$"; // allow optional trailing slash
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public boolean matches(String path, String method, RouteContext ctx) {
        try {
            if (!matchAny(include, path)) {
                return false;
            }
            if (matchAny(exclude, path)) {
                return false;
            }
            if (!methods.isEmpty() && !methods.contains(method)) {
                return false;
            }
            if (condition != null) {
                try {
                    if (!condition.test(ctx)) {
                        return false;
                    }
                } catch (Exception e) {
                    log.warn("{} Condition error", LOG_PREFIX, e);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("{} Match error", LOG_PREFIX, e);
            return false;
        }
    }

    private boolean matchAny(List<Pattern> patterns, String path) {
        if (patterns.isEmpty()) {
            return true;
        }
        for (Pattern p : patterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    boolean isSame(Middleware other) {
        return this.hook == other.hook
                && this.priority == other.priority
                && this.condition == other.condition
                && this.methods.equals(other.methods)
                && this.includeStrings.equals(other.includeStrings)
                && this.excludeStrings.equals(other.excludeStrings);
    }
}

