package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;

import java.util.*;
import java.util.function.Predicate;

/**
 * Encapsulates options to control when a {@link WebHook} should be invoked for a given request.
 * All mutators are fluent and return {@code this}.
 * <p>
 * {@code includes} and {@code excludes} store glob patterns that will be evaluated against
 * canonicalized request paths using extended glob semantics. If the includes array is empty,
 * the hook is considered to match all request paths. Excludes override includes.
 * <p>
 * HTTP method constraints can be declared using {@link #addMethods(HttpMethod...)} or {@link #addMethods(String...)};
 * if no methods are declared then all verbs are accepted. Duplicate methods are ignored.
 * <p>
 * Hooks can be assigned a relative execution priority. Lower values run before higher ones when options
 * are sorted and registration order breaks priority ties. If left at the default 0 all hooks will be invoked
 * in registration sequence unless another has explicit priority.
 * <p>
 * A runtime {@link #predicate(Predicate)} can be used to gate execution of a hook at runtime; if no predicate is
 * set then it will be treated as always true.
 * <p>
 * When {@link #secureMode(boolean)} is set the patterns provided will be scanned for traversal patterns
 * and rejected if they contain disallowed constructs, logging a warning prefixed with {@code SelectiveMiddleware:}.
 */
public class WebHookOptions {

    private final List<String> includes = new ArrayList<>();
    private final List<String> excludes = new ArrayList<>();
    private final Set<HttpMethod> methods = new LinkedHashSet<>();
    private int priority = 0;
    private Predicate<RouteContext> predicate;
    private boolean secureMode;
    private long registrationOrder;

    public WebHookOptions() {
    }

    public WebHookOptions addIncludes(String... patterns) {
        if (patterns == null) return this;
        for (String p : patterns) {
            if (p == null) continue;
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            includes.add(trimmed);
        }
        return this;
    }

    public WebHookOptions addExcludes(String... patterns) {
        if (patterns == null) return this;
        for (String p : patterns) {
            if (p == null) continue;
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            excludes.add(trimmed);
        }
        return this;
    }

    public WebHookOptions addMethods(HttpMethod... methods) {
        if (methods == null) return this;
        for (HttpMethod method : methods) {
            if (method != null) {
                this.methods.add(method);
            }
        }
        return this;
    }

    public WebHookOptions addMethods(String... methods) {
        if (methods == null) return this;
        for (String m : methods) {
            if (m == null) continue;
            String trimmed = m.trim();
            if (trimmed.isEmpty()) continue;
            try {
                HttpMethod method = HttpMethod.valueOf(trimmed.toUpperCase());
                this.methods.add(method);
            } catch (IllegalArgumentException ignored) {
                // silently discard unrecognized strings
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

    public WebHookOptions secureMode(boolean secureMode) {
        this.secureMode = secureMode;
        return this;
    }

    /**
     * Returns the currently configured include patterns.
     */
    public List<String> getIncludes() {
        return includes;
    }

    /**
     * Returns the exclude patterns.
     */
    public List<String> getExcludes() {
        return excludes;
    }

    /**
     * Returns the set of required methods; empty means match all.
     */
    public Set<HttpMethod> getMethods() {
        return methods;
    }

    public int getPriority() {
        return priority;
    }

    public Predicate<RouteContext> getPredicate() {
        return predicate;
    }

    public boolean isSecureMode() {
        return secureMode;
    }

    /**
     * Returns the registration order assigned to this options block.
     */
    public long getRegistrationOrder() {
        return registrationOrder;
    }

    /**
     * Internal-only setter used by registration logic to record a global sequence number.
     */
    public WebHookOptions setRegistrationOrder(long order) {
        this.registrationOrder = order;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebHookOptions that = (WebHookOptions) o;
        return priority == that.priority && secureMode == that.secureMode &&
                Objects.equals(new LinkedHashSet<>(includes), new LinkedHashSet<>(that.includes)) &&
                Objects.equals(new LinkedHashSet<>(excludes), new LinkedHashSet<>(that.excludes)) &&
                Objects.equals(methods, that.methods) &&
                Objects.equals(predicate, that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(new LinkedHashSet<>(includes), new LinkedHashSet<>(excludes), methods, priority, predicate, secureMode);
    }
}
