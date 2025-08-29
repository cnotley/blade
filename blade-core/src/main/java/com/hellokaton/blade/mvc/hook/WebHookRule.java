package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;

import java.util.*;
import java.util.function.Predicate;

/**
 * Options for registering {@link WebHook} middleware.
 *
 * <p>The rule provides fine grained controls on when a middleware
 * should be executed. All fields are optional and will fall back to
 * the defaults described in the selective middleware specification.</p>
 */
public class WebHookRule {

    private final List<String> include = new ArrayList<>();
    private final List<String> exclude = new ArrayList<>();
    private final Set<String> methods = new LinkedHashSet<>();
    private int priority = 0;
    private Predicate<RouteContext> condition = null;

    /**
     * Add include patterns. If none supplied a single pattern of "/" will
     * be used which matches every request path.
     */
    public WebHookRule include(String... patterns) {
        if (patterns != null) {
            Collections.addAll(this.include, patterns);
        }
        return this;
    }

    /**
     * Add exclude patterns.
     */
    public WebHookRule exclude(String... patterns) {
        if (patterns != null) {
            Collections.addAll(this.exclude, patterns);
        }
        return this;
    }

    /**
     * Restrict the hook to the given HTTP methods. The values are case
     * insensitive and will be stored in upper case. Duplicated entries are
     * ignored. Passing an empty array keeps the default of matching all
     * methods.
     */
    public WebHookRule methods(String... methods) {
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isEmpty()) {
                    this.methods.add(m.toUpperCase(Locale.ROOT));
                }
            }
        }
        return this;
    }

    /**
     * Set priority. Lower values will execute earlier. Defaults to {@code 0}.
     */
    public WebHookRule priority(int priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Set runtime condition. If provided it must evaluate to {@code true}
     * for the hook to be executed. If {@code null} the condition always
     * passes.
     */
    public WebHookRule condition(Predicate<RouteContext> condition) {
        this.condition = condition;
        return this;
    }

    public List<String> getInclude() {
        if (this.include.isEmpty()) {
            return Collections.singletonList("/");
        }
        return this.include;
    }

    public List<String> getExclude() {
        return this.exclude;
    }

    public Set<String> getMethods() {
        return this.methods;
    }

    public int getPriority() {
        return this.priority;
    }

    public Predicate<RouteContext> getCondition() {
        return this.condition;
    }
}

