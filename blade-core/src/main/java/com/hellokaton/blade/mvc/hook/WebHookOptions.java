package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for {@link WebHook} registration.
 *
 * <p>This class provides a fluent API to describe when a particular
 * {@link WebHook} should be executed.</p>
 */
public class WebHookOptions {

    private final List<Pattern> includes = new ArrayList<>();
    private final List<Pattern> excludes = new ArrayList<>();
    private final EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
    private int priority = 0;
    private boolean prioritySet = false;
    private Predicate<RouteContext> predicate = null;

    /**
     * Create a new options instance with default settings.
     */
    public WebHookOptions() {
    }

    /**
     * Add include path patterns.
     *
     * @param patterns glob patterns
     * @return this
     */
    public WebHookOptions addIncludes(String... patterns) {
        compilePatterns(includes, patterns);
        return this;
    }

    /**
     * Add exclude path patterns.
     *
     * @param patterns glob patterns
     * @return this
     */
    public WebHookOptions addExcludes(String... patterns) {
        compilePatterns(excludes, patterns);
        return this;
    }

    /**
     * Limit execution to the given HTTP methods.
     *
     * @param methods http methods
     * @return this
     */
    public WebHookOptions addMethods(HttpMethod... methods) {
        if (methods != null) {
            Arrays.stream(methods)
                    .filter(m -> m != null && !this.methods.contains(m))
                    .forEach(this.methods::add);
        }
        return this;
    }

    /**
     * Limit execution to the given HTTP methods.
     *
     * @param methods http method names
     * @return this
     */
    public WebHookOptions addMethods(String... methods) {
        if (methods != null) {
            for (String m : methods) {
                if (m == null) {
                    continue;
                }
                try {
                    HttpMethod method = HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT));
                    this.methods.add(method);
                } catch (IllegalArgumentException ignored) {
                    // ignore unknown verbs
                }
            }
        }
        return this;
    }

    /**
     * Set the priority of the hook. Lower values run earlier.
     *
     * @param p priority
     * @return this
     */
    public WebHookOptions priority(int p) {
        this.priority = p;
        this.prioritySet = true;
        return this;
    }

    /**
     * Set a runtime predicate controlling execution.
     *
     * @param p predicate, {@code null} means always true
     * @return this
     */
    public WebHookOptions predicate(Predicate<RouteContext> p) {
        this.predicate = p;
        return this;
    }

    // ---------------------------------------------------------------------
    // Matching helpers
    // ---------------------------------------------------------------------

    boolean matchesPath(String path) {
        boolean include = includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(path).matches());
        if (!include) {
            return false;
        }
        return excludes.stream().noneMatch(p -> p.matcher(path).matches());
    }

    boolean matchesMethod(HttpMethod method) {
        return methods.isEmpty() || methods.contains(method);
    }

    boolean matchesPredicate(RouteContext ctx) {
        return predicate == null || predicate.test(ctx);
    }

    int getPriority() {
        return priority;
    }

    boolean isPrioritySet() {
        return prioritySet;
    }

    private static void compilePatterns(List<Pattern> container, String... patterns) {
        if (patterns == null) {
            return;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            String normalized = normalizePattern(p);
            container.add(globToPattern(normalized));
        }
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (escaping) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '\\':
                    escaping = true;
                    break;
                default:
                    if ("+()^$.{}[]|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
            }
        }
        if (escaping) {
            regex.append("\\\\");
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String normalizePattern(String pattern) {
        pattern = com.hellokaton.blade.kit.PathKit.fixPath(pattern);
        try {
            URI uri = new URI(pattern);
            pattern = uri.getPath();
        } catch (URISyntaxException ignored) {
        }
        try {
            pattern = URLDecoder.decode(pattern, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
        }
        pattern = pattern.replaceAll("/+", "/");
        if (pattern.length() > 1 && pattern.endsWith("/")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        return pattern.toLowerCase(Locale.ROOT);
    }
}

