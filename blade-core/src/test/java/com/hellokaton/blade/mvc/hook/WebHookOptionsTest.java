package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.route.Route;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import com.hellokaton.blade.mvc.route.WebHookRoute;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class WebHookOptionsTest {

    @Test
    public void testSelectiveMiddlewareMatching() {
        RouteMatcher matcher = new RouteMatcher();
        WebHook hook1 = ctx -> true;
        WebHookOptions opt1 = new WebHookOptions().addInclude("/api/*").addMethods("GET").priority(5);
        matcher.addMiddleware(hook1, opt1);

        WebHook hook2 = ctx -> true;
        WebHookOptions opt2 = new WebHookOptions().addInclude("/api/admin").addExclude("/api/admin/private").priority(1);
        matcher.addMiddleware(hook2, opt2);

        List<Route> matched = matcher.getBefore("GET", "/api/admin");
        assertEquals(2, matched.size());
        assertEquals(hook2, ((WebHookRoute) matched.get(0)).getWebHook());

        List<Route> matchedPost = matcher.getBefore("POST", "/api/admin");
        assertTrue(matchedPost.isEmpty());

        List<Route> matchedExclude = matcher.getBefore("GET", "/api/admin/private");
        assertEquals(1, matchedExclude.size());
        assertEquals(hook1, ((WebHookRoute) matchedExclude.get(0)).getWebHook());
    }

    @Test
    public void testDuplicateIgnored() {
        RouteMatcher matcher = new RouteMatcher();
        WebHook hook = ctx -> true;
        WebHookOptions opt = new WebHookOptions().addInclude("/api/*");
        matcher.addMiddleware(hook, opt);
        matcher.addMiddleware(hook, opt);
        assertEquals(1, matcher.middlewareCount());
    }
}

