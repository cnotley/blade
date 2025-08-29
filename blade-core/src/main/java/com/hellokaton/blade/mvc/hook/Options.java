package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;

/**
 * Options for selective middleware execution.
 * Builder style with chainable methods.
 */
public class Options {

    private static final Predicate<RouteContext> ALWAYS_TRUE = ctx -> true;

    private final List<String> includes = new ArrayList<>();
    private final List<String> excludes = new ArrayList<>();
    private final Set<String> methods = new CopyOnWriteArraySet<>();
    private int priority = 0;
    private Predicate<RouteContext> condition = ALWAYS_TRUE;

    public Options addInclude(String pathPattern) {
        if (pathPattern != null) {
            includes.add(pathPattern);
        }
        return this;
    }

    public Options addExclude(String pathPattern) {
        if (pathPattern != null) {
            excludes.add(pathPattern);
        }
        return this;
    }

    public Options addMethods(String... httpMethods) {
        if (httpMethods != null) {
            for (String m : httpMethods) {
                if (m != null && !m.isEmpty()) {
                    methods.add(m.toUpperCase(Locale.ENGLISH));
                }
            }
        }
        return this;
    }

    public Options priority(int p) {
        this.priority = p;
        return this;
    }

    public Options condition(Predicate<RouteContext> gate) {
        if (gate != null) {
            this.condition = gate;
        }
        return this;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public int getPriority() {
        return priority;
    }

    public Predicate<RouteContext> getCondition() {
        return condition;
    }
}
