package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for WebHook selective invocation.
 */
@Slf4j
@NoArgsConstructor
public class WebHookOptions {

    private final List<Pattern> includes = new ArrayList<>();
    private final List<Pattern> excludes = new ArrayList<>();
    private final Set<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
    private Predicate<RouteContext> predicate;
    private int priority;
    private boolean prioritySet;

    public WebHookOptions addIncludes(String... patterns) {
        addPatterns(includes, patterns);
        return this;
    }

    public WebHookOptions addExcludes(String... patterns) {
        addPatterns(excludes, patterns);
        return this;
    }

    public WebHookOptions addMethods(HttpMethod... methods) {
        if (null != methods) {
            for (HttpMethod m : methods) {
                if (null != m) {
                    this.methods.add(m);
                }
            }
        }
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (null != methods) {
            for (String m : methods) {
                if (null == m) {
                    continue;
                }
                try {
                    this.methods.add(HttpMethod.valueOf(m.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return this;
    }

    public WebHookOptions priority(int p) {
        this.priority = p;
        this.prioritySet = true;
        return this;
    }

    public WebHookOptions predicate(Predicate<RouteContext> p) {
        this.predicate = p;
        return this;
    }

    public boolean matchesPath(String path) {
        try {
            String normalized = normalizePath(path);
            if (!includes.isEmpty()) {
                boolean match = includes.stream().anyMatch(it -> it.matcher(normalized).matches());
                if (!match) {
                    return false;
                }
            }
            for (Pattern p : excludes) {
                if (p.matcher(normalized).matches()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean matchesMethod(HttpMethod method) {
        try {
            if (methods.isEmpty()) {
                return true;
            }
            if (null == method) {
                return false;
            }
            return methods.contains(method);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean matchesPredicate(RouteContext ctx) {
        try {
            return null == predicate || predicate.test(ctx);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: {}", e.getMessage(), e);
            return false;
        }
    }

    public int getPriority() {
        return priority;
    }

    public boolean isPrioritySet() {
        return prioritySet;
    }

    private void addPatterns(List<Pattern> list, String... patterns) {
        if (null == patterns) {
            return;
        }
        for (String pattern : patterns) {
            if (null == pattern) {
                continue;
            }
            String normalized = normalizePattern(pattern);
            list.add(globToRegex(normalized));
        }
    }

    private Pattern globToRegex(String pattern) {
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
                    if ("+()^$.{}[]|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        if (escaping) {
            sb.append("\\\\");
        }
        return Pattern.compile("^" + sb + "$");
    }

    private String normalizePattern(String pattern) {
        String fixed = PathKit.cleanPath(PathKit.fixPath(pattern));
        if (fixed.length() > 1 && fixed.endsWith("/")) {
            fixed = fixed.substring(0, fixed.length() - 1);
        }
        return fixed.toLowerCase(Locale.ROOT);
    }

    public static String normalizePath(String path) {
        String fixed = PathKit.fixPath(path);
        try {
            URI uri = new URI(fixed);
            String p = uri.getPath();
            p = PathKit.cleanPath(p);
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            String p = fixed;
            int idx = p.indexOf('?');
            if (idx >= 0) {
                p = p.substring(0, idx);
            }
            idx = p.indexOf('#');
            if (idx >= 0) {
                p = p.substring(0, idx);
            }
            p = PathKit.cleanPath(p);
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.toLowerCase(Locale.ROOT);
        }
    }
}
