package com.hellokaton.blade.mvc;

import com.hellokaton.blade.BaseTestCase;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.hook.WebHookOptions;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import com.hellokaton.blade.mvc.http.Response;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class SelectiveMiddlewareTest extends BaseTestCase {

    @Test
    public void testSelectiveMiddlewareMatching() {
        RouteMatcher matcher = new RouteMatcher();

        WebHook hook1 = ctx -> true;
        WebHookOptions opt1 = new WebHookOptions().addInclude("/api/*").addMethods("GET").priority(1);

        WebHook hook2 = ctx -> true;
        WebHookOptions opt2 = new WebHookOptions().addInclude("/api/test").priority(0);

        matcher.addMiddleware(hook1, opt1);
        matcher.addMiddleware(hook2, opt2);

        com.hellokaton.blade.mvc.http.HttpRequest request = mockHttpRequest("GET");
        when(request.url()).thenReturn("/api/test");
        when(request.uri()).thenReturn("/api/test");
        when(request.httpMethod()).thenReturn(com.hellokaton.blade.mvc.http.HttpMethod.GET);
        Response response = mockHttpResponse(200);
        RouteContext ctx = new RouteContext(request, response);

        java.util.List<RouteMatcher.Middleware> list = matcher.getMiddleware(ctx);
        assertEquals(2, list.size());
        assertSame(hook2, list.get(0).getHook());
        assertSame(hook1, list.get(1).getHook());

        com.hellokaton.blade.mvc.http.HttpRequest request2 = mockHttpRequest("GET");
        when(request2.url()).thenReturn("/api/test/ignore");
        when(request2.uri()).thenReturn("/api/test/ignore");
        when(request2.httpMethod()).thenReturn(com.hellokaton.blade.mvc.http.HttpMethod.GET);
        RouteContext ctx2 = new RouteContext(request2, response);

        java.util.List<RouteMatcher.Middleware> list2 = matcher.getMiddleware(ctx2);
        assertEquals(1, list2.size());
        assertSame(hook1, list2.get(0).getHook());
    }
}

