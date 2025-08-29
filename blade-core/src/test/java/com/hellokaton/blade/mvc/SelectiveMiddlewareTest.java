package com.hellokaton.blade.mvc;

import com.hellokaton.blade.Blade;
import com.hellokaton.blade.mvc.hook.Options;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.route.Route;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SelectiveMiddlewareTest {

    private Blade blade;

    @Before
    public void setUp() {
        blade = Blade.create();
        WebContext.init(blade, "/");
    }

    @After
    public void tearDown() {
        WebContext.remove();
    }

    private static RouteContext ctx(String uri, String method) {
        return new RouteContext() {
            @Override
            public String uri() { return uri; }
            @Override
            public String method() { return method; }
        };
    }

    static class DummyHook implements WebHook {
        @Override
        public boolean before(RouteContext context) { return true; }
    }

    @Test
    public void testSelectiveMatchingAndOrdering() {
        DummyHook h1 = new DummyHook();
        DummyHook h2 = new DummyHook();
        DummyHook h3 = new DummyHook();

        blade.use(h1, new Options().addInclude("/api/*").addMethods("GET").priority(5));
        blade.use(h2, new Options().addInclude("/api/admin").addExclude("/api/admin/ignore").priority(1));
        blade.use(h3); // default match all

        RouteContext context = ctx("/api/admin", "GET");
        List<Route> routes = blade.routeMatcher().getMiddleware(context);
        assertEquals(3, routes.size());
        assertSame(h2, routes.get(0).getTarget());
        assertSame(h1, routes.get(1).getTarget());
        assertSame(h3, routes.get(2).getTarget());

        RouteContext context2 = ctx("/api/admin/ignore", "GET");
        routes = blade.routeMatcher().getMiddleware(context2);
        assertEquals(2, routes.size());
        assertFalse(routes.stream().anyMatch(r -> r.getTarget() == h2));

        RouteContext context3 = ctx("/api/test", "POST");
        routes = blade.routeMatcher().getMiddleware(context3);
        assertEquals(2, routes.size());
        assertFalse(routes.stream().anyMatch(r -> r.getTarget() == h1));
    }

    @Test
    public void testDuplicateRegistrationIgnored() {
        DummyHook h1 = new DummyHook();
        Options opt = new Options().addInclude("/foo");
        blade.use(h1, opt);
        blade.use(h1, new Options().addInclude("/foo"));
        assertEquals(1, blade.routeMatcher().middlewareCount());
    }
}
