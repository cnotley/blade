package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options used for selective middleware and hook invocation.
 *
 * <p>This class is fluent and every mutator returns {@code this}.</p>
 */
@Slf4j
public class WebHookOptions {

    private static final Pattern SLASHES = Pattern.compile("/+\");

    private final Set<String> includes = new LinkedHashSet<>();
    private final Set<String> excludes = new LinkedHashSet<>();
    private final List<Pattern> includePatterns = new ArrayList<>();
    private final List<Pattern> excludePatterns = new ArrayList<>();

    private EnumSet<HttpMethod> methods = null;

    @Getter
    private int priority = 0;

    @Getter
    private Predicate<RouteContext> predicate = null;

    @Getter
    private long order = 0L;

    public WebHookOptions() {
    }

    /**
     * Include one or more path patterns. Blank entries are ignored.
     */
    public WebHookOptions addIncludes(String... patterns) {
        addPatterns(patterns, true);
        return this;
    }

    /**
     * Exclude one or more path patterns. Blank entries are ignored.
     */
    public WebHookOptions addExcludes(String... patterns) {
        addPatterns(patterns, false);
        return this;
    }

    private void addPatterns(String[] patterns, boolean include) {
        if (null == patterns) return;
        for (String raw : patterns) {
            if (null == raw) continue;
            String p = raw.trim();
            if (p.isEmpty()) continue;
            String norm = normalizePattern(p);
            Pattern compiled = compile(norm);
            if (include) {
                if (includes.add(norm)) {
                    includePatterns.add(compiled);
                }
            } else {
                if (excludes.add(norm)) {
                    excludePatterns.add(compiled);
                }
            }
        }
    }

    private String normalizePattern(String p) {
        String path = PathKit.fixPath(p);
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
        }
        path = SLASHES.matcher(path).replaceAll("/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    private Pattern compile(String pattern) {
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                sb.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
            } else {
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
        }
        if (escaping) {
            log.warn("SelectiveMiddleware: Dangling escape in pattern {}", pattern);
            sb.append(Pattern.quote("\\"));
        }
        try {
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            log.warn("SelectiveMiddleware: {}", e.getMessage());
            return Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        }
    }

    /**
     * Constrain matching to specific HTTP methods.
     */
    public WebHookOptions addMethods(HttpMethod... mths) {
        if (null == mths) return this;
        if (null == methods) {
            methods = EnumSet.noneOf(HttpMethod.class);
        }
        for (HttpMethod m : mths) {
            if (null != m) {
                methods.add(m);
            }
        }
        return this;
    }

    /**
     * Constrain matching to specific HTTP methods from String values.
     * Unrecognised strings are ignored silently.
     */
    public WebHookOptions addMethods(String... mths) {
        if (null == mths) return this;
        for (String m : mths) {
            if (null == m) continue;
            String t = m.trim();
            if (t.isEmpty()) continue;
            try {
                addMethods(HttpMethod.valueOf(t.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return this;
    }

    /**
     * Assign execution priority. Lower numbers run earlier.
     */
    public WebHookOptions priority(int p) {
        this.priority = p;
        return this;
    }

    /**
     * Supply a runtime predicate used to gate execution.
     */
    public WebHookOptions predicate(Predicate<RouteContext> predicate) {
        this.predicate = predicate;
        return this;
    }

    public void setOrder(long order) {
        this.order = order;
    }

    public Set<HttpMethod> methods() {
        return methods == null ? Collections.emptySet() : methods;
    }

    public boolean matchPath(String path) {
        boolean matched = includes.isEmpty();
        if (!includes.isEmpty()) {
            for (Pattern p : includePatterns) {
                if (p.matcher(path).matches()) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) return false;
        for (Pattern p : excludePatterns) {
            if (p.matcher(path).matches()) {
                return false;
            }
        }
        return true;
    }

    public boolean matchMethod(HttpMethod method) {
        return methods == null || methods.isEmpty() || methods.contains(method);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority &&
                Objects.equals(includes, that.includes) &&
                Objects.equals(excludes, that.excludes) &&
                Objects.equals(methods(), that.methods()) &&
                Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(includes, excludes, methods(), priority, predicate);
    }
}

