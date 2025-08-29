package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for selective middleware matching.
 */
public class WebHookOptionsTest {

    private static RouteContext ctx(String method, String uri) {
        return new RouteContext() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public String uri() {
                return uri;
            }
        };
    }

    static class RecordingHook implements WebHook {
        private final StringBuilder sb;
        private final String name;

        RecordingHook(StringBuilder sb, String name) {
            this.sb = sb;
            this.name = name;
        }

        @Override
        public boolean before(RouteContext context) {
            sb.append(name);
            return true;
        }
    }

    @Test
    public void testSelectiveMatch() {
        RouteMatcher matcher = new RouteMatcher();
        StringBuilder sb = new StringBuilder();
        RecordingHook hook = new RecordingHook(sb, "A");
        WebHookOptions opt = new WebHookOptions()
                .addInclude("/api/*")
                .addExclude("/api/skip")
                .addMethods("GET")
                .condition(c -> c.uri().contains("hit"));
        matcher.addMiddleware(hook, opt);

        // should match
        RouteContext c1 = ctx("GET", "/API/hit/data?x=1");
        matcher.getMiddleware(c1).forEach(w -> w.getHook().before(c1));
        Assert.assertEquals("A", sb.toString());

        // excluded path
        RouteContext c2 = ctx("GET", "/api/skip");
        matcher.getMiddleware(c2).forEach(w -> w.getHook().before(c2));
        Assert.assertEquals("A", sb.toString());

        // wrong method
        RouteContext c3 = ctx("POST", "/api/hit/data");
        matcher.getMiddleware(c3).forEach(w -> w.getHook().before(c3));
        Assert.assertEquals("A", sb.toString());

        // predicate fails
        RouteContext c4 = ctx("GET", "/api/miss/data");
        matcher.getMiddleware(c4).forEach(w -> w.getHook().before(c4));
        Assert.assertEquals("A", sb.toString());
    }

    @Test
    public void testPriorityOrdering() {
        RouteMatcher matcher = new RouteMatcher();
        StringBuilder sb = new StringBuilder();
        RecordingHook h1 = new RecordingHook(sb, "1");
        RecordingHook h2 = new RecordingHook(sb, "2");
        matcher.addMiddleware(h1, new WebHookOptions().priority(5));
        matcher.addMiddleware(h2, new WebHookOptions().priority(1));
        RouteContext ctx = ctx("GET", "/");
        matcher.getMiddleware(ctx).forEach(w -> w.getHook().before(ctx));
        Assert.assertEquals("21", sb.toString());
    }

    @Test
    public void testDuplicateIgnored() {
        RouteMatcher matcher = new RouteMatcher();
        RecordingHook hook = new RecordingHook(new StringBuilder(), "A");
        WebHookOptions opt = new WebHookOptions();
        matcher.addMiddleware(hook, opt);
        matcher.addMiddleware(hook, opt);
        Assert.assertEquals(1, matcher.middlewareCount());
    }
}
