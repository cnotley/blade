package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.Blade;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.Request;
import com.hellokaton.blade.mvc.http.Response;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for selective middleware registration and matching.
 */
public class SelectiveMiddlewareTest {

    private RouteContext ctx(String method, String uri) {
        Request req = mock(Request.class);
        when(req.method()).thenReturn(method);
        when(req.uri()).thenReturn(uri);
        return new RouteContext(req, mock(Response.class));
    }

    @Test
    public void testBasicFiltering() {
        Blade blade = Blade.create();
        WebHook all = mock(WebHook.class);
        blade.use(all);

        WebHook selective = mock(WebHook.class);
        WebHookRule rule = new WebHookRule()
                .include("/foo/*")
                .exclude("/foo/skip")
                .methods("GET");
        blade.use(selective, rule);

        List<Middleware> m1 = blade.routeMatcher().getMiddleware(ctx("GET", "/foo/bar"));
        assertEquals(2, m1.size());
        assertSame(all, m1.get(0).getHook());
        assertSame(selective, m1.get(1).getHook());

        List<Middleware> m2 = blade.routeMatcher().getMiddleware(ctx("GET", "/foo/skip"));
        assertEquals(1, m2.size());
        assertSame(all, m2.get(0).getHook());

        List<Middleware> m3 = blade.routeMatcher().getMiddleware(ctx("POST", "/foo/bar"));
        assertEquals(1, m3.size());
        assertSame(all, m3.get(0).getHook());
    }

    @Test
    public void testPriorityOrder() {
        Blade blade = Blade.create();
        WebHook h1 = mock(WebHook.class);
        WebHook h2 = mock(WebHook.class);
        WebHook h3 = mock(WebHook.class);

        blade.use(h1, new WebHookRule().priority(5));
        blade.use(h2, new WebHookRule().priority(5));
        blade.use(h3, new WebHookRule().priority(-1));

        List<Middleware> m = blade.routeMatcher().getMiddleware(ctx("GET", "/"));
        assertEquals(3, m.size());
        assertSame(h3, m.get(0).getHook());
        assertSame(h1, m.get(1).getHook());
        assertSame(h2, m.get(2).getHook());
    }

    @Test
    public void testCondition() {
        Blade blade = Blade.create();
        WebHook cond = mock(WebHook.class);
        WebHookRule rule = new WebHookRule().condition(c -> false);
        blade.use(cond, rule);

        List<Middleware> m = blade.routeMatcher().getMiddleware(ctx("GET", "/"));
        assertTrue(m.isEmpty());
    }

    @Test
    public void testDuplicateIgnored() {
        Blade blade = Blade.create();
        WebHook hook = mock(WebHook.class);
        blade.use(hook);
        blade.use(hook); // duplicate

        List<Middleware> m = blade.routeMatcher().getMiddleware(ctx("GET", "/"));
        assertEquals(1, m.size());
    }
}

