package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.http.HttpMethod;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;

public class WebHookOptionsTest {

    @Test
    public void testOptions() {
        WebHookOptions options = new WebHookOptions()
                .addIncludes("/foo/*")
                .addExcludes("/foo/bar")
                .addMethods(HttpMethod.GET, HttpMethod.POST)
                .addMethods("PUT", "UNKNOWN")
                .priority(5)
                .predicate(ctx -> ctx == null);

        Assert.assertTrue(options.matchesPath("/foo/baz"));
        Assert.assertFalse(options.matchesPath("/foo/bar"));
        Assert.assertTrue(options.matchesMethod(HttpMethod.GET));
        Assert.assertTrue(options.matchesMethod(HttpMethod.POST));
        Assert.assertTrue(options.matchesMethod(HttpMethod.PUT));
        Assert.assertFalse(options.matchesMethod(HttpMethod.DELETE));

        RouteContext ctx = null;
        Assert.assertTrue(options.matchesPredicate(ctx));
        RouteContext ctx2 = mock(RouteContext.class);
        Assert.assertFalse(options.matchesPredicate(ctx2));
    }
}

