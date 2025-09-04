import com.hellokaton.blade.Blade;
import com.hellokaton.blade.kit.PathKit;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.hook.WebHookOptions;
import com.hellokaton.blade.mvc.http.HttpMethod;
import com.hellokaton.blade.mvc.route.Route;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import org.junit.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class SelectiveMiddlewareTest {

    private static final class TestRouteContext extends RouteContext {
        private String method;
        private String uri;
        private final Map<String, String> pathParams = new HashMap<>();
        private StringBuilder body = new StringBuilder();

        TestRouteContext(String method, String uri) {
            this.method = method;
            this.uri = uri;
        }

        void setMethod(String m) {
            this.method = m;
        }

        void setUri(String u) {
            this.uri = u;
        }

        void setPathParam(String k, String v) {
            this.pathParams.put(k, v);
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String pathString(String variable) {
            return pathParams.get(variable);
        }

        @Override
        public void text(String text) {
            body.setLength(0);
            body.append(text);
        }

        String getBody() {
            return body.toString();
        }
    }

    private static final class RecordingWebHook implements WebHook {
        final String name;
        final List<String> sink;
        final AtomicInteger beforeCount = new AtomicInteger(0);
        final AtomicInteger afterCount = new AtomicInteger(0);

        RecordingWebHook(String name, List<String> sink) {
            this.name = name;
            this.sink = sink;
        }

        @Override
        public boolean before(RouteContext ctx) {
            sink.add("before:" + name + ":" + ctx.method() + ":" + ctx.uri());
            beforeCount.incrementAndGet();
            return true;
        }

        @Override
        public boolean after(RouteContext ctx) {
            sink.add("after:" + name + ":" + ctx.method() + ":" + ctx.uri());
            afterCount.incrementAndGet();
            return true;
        }
    }

    private static final class RecordingBefore implements RouteHandler {
        final String name;
        final List<String> sink;

        RecordingBefore(String name, List<String> sink) {
            this.name = name;
            this.sink = sink;
        }

        @Override
        public void handle(RouteContext ctx) {
            sink.add("beforeRoute:" + name + ":" + ctx.uri());
        }

        @Override
        public String toString() {
            return "RecordingBefore(" + name + ")";
        }
    }

    private static RouteHandler text(String s) {
        return ctx -> ctx.text(s);
    }

    private static WebHookOptions options() {
        return new WebHookOptions();
    }

    private static void addMethodsReflective(WebHookOptions opt, String... verbs) {
        try {
            Method m = null;
            Method[] all = opt.getClass().getMethods();
            for (int i = 0; i < all.length; i++) {
                Method mm = all[i];
                if (!"addMethods".equals(mm.getName()))
                    continue;
                Class<?>[] p = mm.getParameterTypes();
                if (p.length == 1 && p[0].isArray()) {
                    Class<?> c = p[0].getComponentType();
                    if (c == String.class || (c != null && CharSequence.class.isAssignableFrom(c))) {
                        m = mm;
                        break;
                    }
                }
            }
            assertNotNull("Expected addMethods(String...) on WebHookOptions for unknown-verb testing", m);
            Object arr = Array.newInstance(m.getParameterTypes()[0].getComponentType(), verbs.length);
            for (int i = 0; i < verbs.length; i++)
                Array.set(arr, i, verbs[i]);
            m.invoke(opt, arr);
        } catch (Exception e) {
            throw new AssertionError("Reflection addMethods(String...) failed: " + e, e);
        }
    }

    private static List<WebHook> findRegisteredWebHooks(Blade blade) {
        List<WebHook> result = new ArrayList<WebHook>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Deque<Object> stack = new ArrayDeque<Object>();
        stack.push(blade);
        int depth = 0;
        int maxDepth = 4;

        while (!stack.isEmpty() && depth <= maxDepth) {
            Object o = stack.pop();
            if (o == null || seen.contains(o))
                continue;
            seen.add(o);

            if (o instanceof WebHook) {
                result.add((WebHook) o);
            }

            Class<?> oc = o.getClass();
            if (oc.isArray()) {
                int len = Array.getLength(o);
                for (int i = 0; i < len; i++)
                    stack.push(Array.get(o, i));
                continue;
            }

            if (o instanceof Collection) {
                for (Object e : (Collection<?>) o)
                    stack.push(e);
                continue;
            }

            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    stack.push(e.getKey());
                    stack.push(e.getValue());
                }
                continue;
            }

            Package pkg = oc.getPackage();
            String pn = (pkg == null) ? "" : pkg.getName();
            if (!pn.startsWith("com.hellokaton"))
                continue;

            Field[] fs = oc.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                try {
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(o);
                    if (v != null)
                        stack.push(v);
                } catch (Throwable ignore) {
                }
            }
            depth++;
        }
        List<WebHook> dedup = new ArrayList<WebHook>();
        Set<WebHook> seenHooks = Collections.newSetFromMap(new IdentityHashMap<WebHook, Boolean>());
        for (int i = 0; i < result.size(); i++) {
            WebHook h = result.get(i);
            if (!seenHooks.contains(h)) {
                dedup.add(h);
                seenHooks.add(h);
            }
        }
        return dedup;
    }

    private static void dispatchBeforeChain(RouteMatcher rm, String path, TestRouteContext ctx) {
        List<Route> routes = rm.getBefore(path);
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            RouteHandler h = (RouteHandler) r.getTarget();
            try {
                h.handle(ctx);
            } catch (Exception e) {
            }
        }
    }

    @Test
    public void testWebHookOptionsCtorExists() throws Exception {
        Class<?> c = Class.forName("com.hellokaton.blade.mvc.hook.WebHookOptions");
        assertNotNull(c.getConstructor());
    }

    @Test
    public void testWebHookOptionsFluentIncludes() {
        WebHookOptions opts = new WebHookOptions();
        assertSame(opts, opts.addIncludes("/a", "/b"));
    }

    @Test
    public void testWebHookOptionsFluentExcludes() {
        WebHookOptions opts = new WebHookOptions();
        assertSame(opts, opts.addExcludes("/c"));
    }

    @Test
    public void testWebHookOptionsFluentMethodsHttpMethod() {
        WebHookOptions opts = new WebHookOptions();
        assertSame(opts, opts.addMethods(HttpMethod.GET, HttpMethod.POST));
    }

    @Test
    public void testWebHookOptionsFluentPriority() {
        WebHookOptions opts = new WebHookOptions();
        assertSame(opts, opts.priority(3));
    }

    @Test
    public void testWebHookOptionsFluentPredicate() {
        WebHookOptions opts = new WebHookOptions();
        assertSame(opts, opts.predicate(new Predicate<RouteContext>() {
            @Override
            public boolean test(RouteContext routeContext) {
                return true;
            }
        }));
    }

    @Test
    public void testIncludeMatching() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b1 = new RecordingBefore("inc", sink);
        WebHookOptions o = options().addIncludes("/api/*");
        blade.before("/*", b1, o);

        List<Route> r1 = rm.getBefore("/api/users");
        assertTrue("Expected include to match '/api/users'", containsTarget(r1, b1));

        List<Route> r2 = rm.getBefore("/static/style.css");
        assertFalse("Include should not match '/static/style.css'", containsTarget(r2, b1));
    }

    @Test
    public void testExcludeMatching() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("incExc", sink);
        WebHookOptions o = options().addIncludes("/api/*").addExcludes("/api/private/*");
        blade.before("/*", b, o);

        assertTrue(containsTarget(rm.getBefore("/api/open/list"), b));
        assertFalse("Exclude should override include", containsTarget(rm.getBefore("/api/private/list"), b));
    }

    @Test
    public void testStarWildcard() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("star", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/pub/*/assets/*"));

        assertTrue(containsTarget(rm.getBefore("/pub/x/assets/a/b/c.png"), b));
    }

    @Test
    public void testQuestionWildcard() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("q", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/file/?.txt"));

        assertTrue(containsTarget(rm.getBefore("/file/a.txt"), b));
        assertFalse(containsTarget(rm.getBefore("/file/ab.txt"), b));
    }

    @Test
    public void testCaseInsensitiveMatching() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("ci", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/API/USERS/*"));

        assertTrue(containsTarget(rm.getBefore("/api/users/42"), b));
        assertTrue(containsTarget(rm.getBefore("/Api/Users/42"), b));
    }

    @Test
    public void testEscapedCharacters() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("esc", new CopyOnWriteArrayList<String>());

        blade.before("/*", b, options().addIncludes("/literal/\\*\\?\\\\"));

        assertTrue("Escaped metacharacters treated as literals",
                containsTarget(rm.getBefore("/literal/*?\\"), b));
        assertFalse(containsTarget(rm.getBefore("/literal/star"), b));

        RecordingBefore b2 = new RecordingBefore("esc-nested", new CopyOnWriteArrayList<String>());
        blade.before("/*", b2, options().addIncludes("/literal2/\\\\\\\\*"));

        assertTrue("Two backslashes before '*' should represent literal '\\*'",
                containsTarget(rm.getBefore("/literal2/\\\\*"), b2));

        assertFalse("Backslash + '*' must not become wildcard when backslashes are doubled",
                containsTarget(rm.getBefore("/literal2/\\\\anything"), b2));
    }

    @Test
    public void testSpecifiedMethodMatching() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("m", sink);

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        blade.before("/*", b, options().addMethods(HttpMethod.GET));

        TestRouteContext ctx = new TestRouteContext("GET", "/echo");
        dispatchBeforeChain(rm, "/echo", ctx);
        assertEquals(1, countSink(sink, "beforeRoute:m:/echo"));

        ctx.setMethod("POST");
        dispatchBeforeChain(rm, "/echo", ctx);
        assertEquals("POST should not match when only GET specified",
                1, countSink(sink, "beforeRoute:m:/echo"));
    }

    @Test
    public void testEmptyMethodMatching() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("all", sink);

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        blade.before("/*", b, options());

        TestRouteContext ctx = new TestRouteContext("GET", "/e");
        dispatchBeforeChain(rm, "/e", ctx);
        ctx.setMethod("POST");
        dispatchBeforeChain(rm, "/e", ctx);
        ctx.setMethod("PUT");
        dispatchBeforeChain(rm, "/e", ctx);

        assertTrue("Should be invoked at least 3 times",
                countSink(sink, "beforeRoute:all:/e") >= 3);
    }

    @Test
    public void testUnknownVerbs() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("unknown", sink);
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        WebHookOptions o = options();
        addMethodsReflective(o, "BREW", "GET", "WHATISTHIS");
        blade.before("/*", b, o);

        TestRouteContext ctx = new TestRouteContext("GET", "/u");
        dispatchBeforeChain(rm, "/u", ctx);
        int afterGET = countSink(sink, "beforeRoute:unknown:/u");

        ctx.setMethod("POST");
        dispatchBeforeChain(rm, "/u", ctx);
        assertEquals("Unknown verbs must not match; only GET should", afterGET,
                countSink(sink, "beforeRoute:unknown:/u"));
    }

    @Test
    public void testPredicateGating() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("pred", sink);
        final AtomicBoolean evaluated = new AtomicBoolean(false);
        Predicate<RouteContext> p = new Predicate<RouteContext>() {
            @Override
            public boolean test(RouteContext ctx) {
                evaluated.set(true);
                return "/allow".equalsIgnoreCase(ctx.uri());
            }
        };

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        blade.before("/*", b, options().predicate(p));

        TestRouteContext ctx = new TestRouteContext("GET", "/allow");
        dispatchBeforeChain(rm, "/allow", ctx);
        assertTrue(evaluated.get());
        int countAfterAllow = countSink(sink, "beforeRoute:pred:/allow");

        evaluated.set(false);
        ctx.setUri("/deny");
        dispatchBeforeChain(rm, "/deny", ctx);
        assertTrue(evaluated.get());
        assertEquals("Predicate false => middleware skipped",
                countAfterAllow, countSink(sink, "beforeRoute:pred:/allow"));
        assertEquals(0, countSink(sink, "beforeRoute:pred:/deny"));
    }

    @Test
    public void testNullPredicate() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("nullp", sink);
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        blade.before("/*", b, options().predicate(null));

        TestRouteContext ctx = new TestRouteContext("GET", "/np");
        dispatchBeforeChain(rm, "/np", ctx);
        assertEquals(1, countSink(sink, "beforeRoute:nullp:/np"));
    }

    @Test
    public void testPriorityAndOrder() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b1 = new RecordingBefore("p10", sink);
        RecordingBefore b2 = new RecordingBefore("p0_first", sink);
        RecordingBefore b3 = new RecordingBefore("p0_second", sink);
        RecordingBefore b4 = new RecordingBefore("p-5", sink);

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        blade.before("/*", b2, options().priority(0));
        blade.before("/*", b3, options().priority(0));
        blade.before("/*", b1, options().priority(10));
        blade.before("/*", b4, options().priority(-5));

        TestRouteContext ctx = new TestRouteContext("GET", "/order");
        dispatchBeforeChain(rm, "/order", ctx);

        List<String> expected = Arrays.asList(
                "beforeRoute:p-5:/order",
                "beforeRoute:p0_first:/order",
                "beforeRoute:p0_second:/order",
                "beforeRoute:p10:/order");
        assertEquals(expected, sink);
    }

    @Test
    public void testGlobalUse() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingWebHook a = new RecordingWebHook("A", sink);
        RecordingWebHook b = new RecordingWebHook("B", sink);

        Blade blade = Blade.create();

        try {
            Method mSingle = Blade.class.getMethod("use", WebHook.class);
            mSingle.invoke(blade, a);
            mSingle.invoke(blade, b);
        } catch (NoSuchMethodException nsme) {
            try {
                Method mVarargs = Blade.class.getMethod("use", WebHook[].class);
                mVarargs.invoke(blade, (Object) new WebHook[] { a });
                mVarargs.invoke(blade, (Object) new WebHook[] { b });
            } catch (Exception e) {
                throw new AssertionError("Neither use(WebHook) nor use(WebHook...) available", e);
            }
        } catch (Exception e) {
            throw new AssertionError("Invoking Blade.use failed", e);
        }

        List<WebHook> hooks = findRegisteredWebHooks(blade);
        TestRouteContext ctx1 = new TestRouteContext("GET", "/p1");
        for (int i = 0; i < hooks.size(); i++)
            hooks.get(i).before(ctx1);

        TestRouteContext ctx2 = new TestRouteContext("POST", "/p2");
        for (int i = 0; i < hooks.size(); i++)
            hooks.get(i).before(ctx2);

        assertTrue("A should run at least once", a.beforeCount.get() >= 1);
        assertTrue("B should run at least once", b.beforeCount.get() >= 1);

        int aFirstIdx = indexOfPrefix(sink, "before:A:");
        int bFirstIdx = indexOfPrefix(sink, "before:B:");
        assertTrue("Registration order must be preserved",
                aFirstIdx != -1 && bFirstIdx != -1 && aFirstIdx < bFirstIdx);
    }

    @Test
    public void testLegacyRootPattern() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        RecordingBefore bRoot = new RecordingBefore("root", new CopyOnWriteArrayList<String>());
        blade.before("/*", bRoot);
        assertTrue(containsTarget(rm.getBefore("/any/path"), bRoot));
        assertTrue(containsTarget(rm.getBefore("/"), bRoot));
        assertTrue(containsTarget(rm.getBefore("/deep/nested/path"), bRoot));

        RecordingBefore bGlob = new RecordingBefore("glob", new CopyOnWriteArrayList<String>());
        blade.before("/api/[0-9a-f]/user/*", bGlob);
        assertTrue(containsTarget(rm.getBefore("/api/5/user/details"), bGlob));
        assertTrue(containsTarget(rm.getBefore("/api/a/user/"), bGlob));
        assertFalse(containsTarget(rm.getBefore("/api/g/user/details"), bGlob));
        assertFalse(containsTarget(rm.getBefore("/api/5/other/details"), bGlob));
        assertFalse(containsTarget(rm.getBefore("/other"), bGlob));
    }

    @Test
    public void testDefaultPriority() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        RecordingBefore def = new RecordingBefore("def", new CopyOnWriteArrayList<String>());
        RecordingBefore p1 = new RecordingBefore("p1", new CopyOnWriteArrayList<String>());
        RecordingBefore pm1 = new RecordingBefore("pm1", new CopyOnWriteArrayList<String>());

        blade.before("/*", def);
        blade.before("/*", p1, options().priority(1));
        blade.before("/*", pm1, options().priority(-1));

        List<Route> routes = rm.getBefore("/x");
        assertOrder(routes, pm1, def, p1);
    }

    @Test
    public void testOverloadedUse() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingWebHook underlying = new RecordingWebHook("sel", sink);

        Blade blade = Blade.create();

        List<WebHook> before = findRegisteredWebHooks(blade);

        WebHookOptions o = options()
                .addIncludes("/only")
                .addExcludes("/skip")
                .addMethods(HttpMethod.GET)
                .priority(5)
                .predicate(new Predicate<RouteContext>() {
                    @Override
                    public boolean test(RouteContext ctx) {
                        return true;
                    }
                });

        try {
            Method m = Blade.class.getMethod("use", WebHook.class, WebHookOptions.class);
            m.invoke(blade, underlying, o);
        } catch (Exception e) {
            throw new AssertionError("Blade.use(WebHook, WebHookOptions) overload missing or failed", e);
        }

        List<WebHook> after = findRegisteredWebHooks(blade);
        assertTrue("A new WebHook should have been registered", after.size() >= before.size() + 1);

        WebHook newlyAdded = null;
        for (int i = 0; i < after.size(); i++) {
            WebHook h = after.get(i);
            if (!before.contains(h)) {
                newlyAdded = h;
                break;
            }
        }
        assertNotNull("Failed to locate newly registered selective WebHook", newlyAdded);

        TestRouteContext ctx = new TestRouteContext("GET", "/only");
        newlyAdded.before(ctx);
        int c1 = underlying.beforeCount.get();

        ctx.setUri("/skip");
        newlyAdded.before(ctx);
        assertEquals("Excluded path must not execute", c1, underlying.beforeCount.get());
    }

    @Test
    public void testOverloadedBefore() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore b = new RecordingBefore("bf", sink);

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        blade.before("/api/*", b,
                options()
                        .addMethods(HttpMethod.POST)
                        .predicate(new Predicate<RouteContext>() {
                            @Override
                            public boolean test(RouteContext ctx) {
                                return "/api/yes".equalsIgnoreCase(ctx.uri());
                            }
                        }));

        TestRouteContext ctx = new TestRouteContext("POST", "/api/yes");
        dispatchBeforeChain(rm, "/api/yes", ctx);
        assertTrue(containsOnce(sink, "beforeRoute:bf:/api/yes"));

        sink.clear();
        ctx.setUri("/api/no");
        dispatchBeforeChain(rm, "/api/no", ctx);
        assertFalse(containsPrefix(sink, "beforeRoute:bf:/api/no"));

        sink.clear();
        ctx.setMethod("GET");
        ctx.setUri("/api/yes");
        dispatchBeforeChain(rm, "/api/yes", ctx);
        assertFalse(containsPrefix(sink, "beforeRoute:bf:/api/yes"));
    }

    @Test
    public void testSlashNormalization() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        
        RecordingBefore b = new RecordingBefore("norm", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/a/b/c"));
        
        assertTrue(containsTarget(rm.getBefore("/a//b///c/"), b));
        assertTrue(containsTarget(rm.getBefore("/a/b/c"), b));
        assertTrue(containsTarget(rm.getBefore("/a/b/c/"), b));
        
        RecordingBefore b2 = new RecordingBefore("norm-slashes", new CopyOnWriteArrayList<String>());
        blade.before("/*", b2, options().addIncludes("/x//y"));
        
        assertTrue("Duplicate slashes in pattern should match normalized path", 
                containsTarget(rm.getBefore("/x/y"), b2));
        assertTrue("Multiple slashes normalize correctly", 
                containsTarget(rm.getBefore("/x///y"), b2));
    }

    @Test
    public void testPercentDecoding() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("cafe", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/cafâ��Â©"));

        assertTrue(containsTarget(rm.getBefore("/caf%C3%A9"), b));
    }

    @Test
    public void testQueryFragmentStripping() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("qf", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/x/y"));

        assertTrue(containsTarget(rm.getBefore("/x/y?z=1#frag"), b));
    }

    @Test
    public void testMatchingSequence() {
        RecordingBefore b = new RecordingBefore("seq", new CopyOnWriteArrayList<String>());
        final AtomicBoolean predicateEvaluated = new AtomicBoolean(false);

        WebHookOptions o = options()
                .addIncludes("/ok/*")
                .addExcludes("/ok/blocked/*")
                .addMethods(HttpMethod.POST)
                .predicate(new Predicate<RouteContext>() {
                    @Override
                    public boolean test(RouteContext ctx) {
                        predicateEvaluated.set(true);
                        return true;
                    }
                });

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        blade.before("/*", b, o);

        TestRouteContext ctx = new TestRouteContext("POST", "/ok/blocked/x");
        predicateEvaluated.set(false);
        dispatchBeforeChain(rm, "/ok/blocked/x", ctx);
        assertFalse("Predicate must not run for excluded path", predicateEvaluated.get());
        assertEquals(0, countSink(b.sink, "beforeRoute:seq:/ok/blocked/x"));

        ctx.setMethod("GET");
        ctx.setUri("/ok/allowed");
        predicateEvaluated.set(false);
        dispatchBeforeChain(rm, "/ok/allowed", ctx);
        assertFalse("Predicate must not run when method doesn't match", predicateEvaluated.get());
        assertEquals(0, countSink(b.sink, "beforeRoute:seq:/ok/allowed"));

        ctx.setMethod("POST");
        ctx.setUri("/ok/allowed");
        predicateEvaluated.set(false);
        dispatchBeforeChain(rm, "/ok/allowed", ctx);
        assertTrue(predicateEvaluated.get());
        assertEquals(1, countSink(b.sink, "beforeRoute:seq:/ok/allowed"));
    }

    @Test
    public void testDynamicDuringExecution() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore bGet = new RecordingBefore("bg", new CopyOnWriteArrayList<String>());
        RecordingBefore bPost = new RecordingBefore("bp", new CopyOnWriteArrayList<String>());

        blade.before("/*", bGet, options().addMethods(HttpMethod.GET));
        blade.before("/*", bPost, options().addMethods(HttpMethod.POST));

        List<Route> routes = rm.getBefore("/foo");
        assertTrue(containsTarget(routes, bGet));
        assertTrue(containsTarget(routes, bPost));
    }

    @Test
    public void testConcurrentReads() throws Exception {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        for (int i = 0; i < 32; i++) {
            blade.before("/*", new RecordingBefore("b" + i, new CopyOnWriteArrayList<String>()),
                    options().addIncludes("/p/*").priority(i % 5 - 2));
        }

        final int threads = 8;
        final int itersPerThread = 2000;
        final CountDownLatch latch = new CountDownLatch(threads);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

        Runnable reader = new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < itersPerThread; i++) {
                        String p = "/p/x" + (i % 10);
                        List<Route> routes = rm.getBefore(p);
                        int size = routes.size();
                        if (size < 0)
                            throw new AssertionError();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            }
        };

        for (int i = 0; i < threads; i++)
            new Thread(reader, "reader-" + i).start();
        assertTrue("Readers finished", latch.await(10, TimeUnit.SECONDS));
        if (!errors.isEmpty())
            fail("Concurrent reads produced errors: " + errors.get(0));
    }

    @Test
    public void testConcurrentRegistrations() throws Exception {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        int writers = 2, readers = 6;
        CountDownLatch all = new CountDownLatch(writers + readers);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

        Runnable writer = new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 500; i++) {
                        blade.before("/*",
                                new RecordingBefore("w" + i, new CopyOnWriteArrayList<String>()),
                                options().addIncludes("/c/*").priority(ThreadLocalRandom.current().nextInt(-2, 3)));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    all.countDown();
                }
            }
        };
        Runnable reader = new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 3000; i++) {
                        List<Route> rs = rm.getBefore("/c/k" + (i % 7));
                        for (int j = 0; j < rs.size(); j++) {
                            if (rs.get(j) == null)
                                throw new AssertionError("null route");
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    all.countDown();
                }
            }
        };

        for (int i = 0; i < writers; i++)
            new Thread(writer, "writer-" + i).start();
        for (int i = 0; i < readers; i++)
            new Thread(reader, "reader-" + i).start();

        assertTrue(all.await(15, TimeUnit.SECONDS));
        if (!errors.isEmpty())
            fail("Concurrency errors: " + errors.get(0));
    }

    @Test
    public void testEmptyIncludes() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("any", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes());
        assertTrue(containsTarget(rm.getBefore("/x/y/z"), b));
    }

    @Test
    public void testEmptyExcludes() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("noexc", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("/x/*").addExcludes());
        assertTrue(containsTarget(rm.getBefore("/x/qq"), b));
    }

    @Test
    public void testPatternTrimming() {
        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();
        RecordingBefore b = new RecordingBefore("trim", new CopyOnWriteArrayList<String>());
        blade.before("/*", b, options().addIncludes("   /t/ok   ", "   "));
        assertTrue(containsTarget(rm.getBefore("/t/ok"), b));
    }

    @Test
    public void testDuplicateRegistrations() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore dup = new RecordingBefore("dup", sink);

        final Predicate<RouteContext> p = new Predicate<RouteContext>() {
            @Override
            public boolean test(RouteContext ctx) {
                return true;
            }
        };

        WebHookOptions a = options().addIncludes("/dup/*").addExcludes("/dup/none")
                .addMethods(HttpMethod.GET).priority(0).predicate(p);
        WebHookOptions b = options().addIncludes("/dup/*").addExcludes("/dup/none")
                .addMethods(HttpMethod.GET).priority(0).predicate(p);

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        blade.before("/*", dup, a);
        blade.before("/*", dup, b);

        List<Route> routes = rm.getBefore("/dup/ok");
        assertEquals("Duplicate registration must be ignored",
                1, countTargets(routes, dup));
    }

    @Test
    public void testPredicateEquality() {
        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore handler = new RecordingBefore("predEq", sink);

        class EqPred implements Predicate<RouteContext> {
            final String id;

            EqPred(String id) {
                this.id = id;
            }

            public boolean test(RouteContext routeContext) {
                return true;
            }

            @Override
            public boolean equals(Object o) {
                return (o instanceof EqPred) && Objects.equals(id, ((EqPred) o).id);
            }

            @Override
            public int hashCode() {
                return Objects.hash(id);
            }
        }
        Predicate<RouteContext> p1 = new EqPred("X");
        Predicate<RouteContext> p2 = new EqPred("X");

        Blade blade = Blade.create();
        RouteMatcher rm = blade.routeMatcher();

        blade.before("/*", handler, options().predicate(p1));
        blade.before("/*", handler, options().predicate(p2));

        List<Route> routes = rm.getBefore("/e");
        assertEquals(1, countTargets(routes, handler));
    }

@Test
public void testExceptionHandling() {
    final List<String> sink = new CopyOnWriteArrayList<String>();
    final AtomicBoolean predicateCalled = new AtomicBoolean(false);
    
    RecordingBefore withBadPredicate = new RecordingBefore("badpred", sink);
    RecordingBefore normalHandler = new RecordingBefore("normal", sink);
    
    Blade blade = Blade.create();
    RouteMatcher rm = blade.routeMatcher();
    
    blade.before("/*", withBadPredicate, options().predicate(ctx -> {
        predicateCalled.set(true);
        throw new RuntimeException("predicate error");
    }));
    blade.before("/*", normalHandler);
    
    TestRouteContext ctx = new TestRouteContext("GET", "/test");
    dispatchBeforeChain(rm, "/test", ctx);
    
    assertTrue("Predicate was called", predicateCalled.get());
    assertFalse("Handler with failing predicate should not execute", 
                containsPrefix(sink, "beforeRoute:badpred"));
    assertTrue("Normal handler should still run", 
              containsOnce(sink, "beforeRoute:normal:/test"));
}

    @Test
    public void testPublicApiMethodsExist() throws Exception {
        assertNotNull(Blade.class.getMethod("before", String.class, RouteHandler.class));
        assertNotNull(Blade.class.getMethod("before", String.class, RouteHandler.class, WebHookOptions.class));

        boolean legacyUseOk;
        try {
            Blade.class.getMethod("use", WebHook.class);
            legacyUseOk = true;
        } catch (NoSuchMethodException e) {
            Blade.class.getMethod("use", WebHook[].class);
            legacyUseOk = true;
        }
        assertTrue("Legacy Blade.use(...) not found", legacyUseOk);

        assertNotNull(Blade.class.getMethod("use", WebHook.class, WebHookOptions.class));
    }

    @Test
    public void testExemptRouteLevel() {
        Blade blade = Blade.create();
        blade.before("/*", new RecordingBefore("noop", new CopyOnWriteArrayList<String>()),
                options().addIncludes("/users/*").predicate(new Predicate<RouteContext>() {
                    @Override
                    public boolean test(RouteContext ctx) {
                        return true;
                    }
                }).priority(1));

        List<String> sink = new CopyOnWriteArrayList<String>();
        RecordingBefore legacyBefore = new RecordingBefore("legacy-route", sink);
        blade.before("/users/:id", legacyBefore); 

        RouteMatcher rm = blade.routeMatcher();

        RouteHandler handler = new RouteHandler() {
            @Override
            public void handle(RouteContext ctx) {
                ctx.text("user#" + ctx.pathString("id"));
            }
        };

        TestRouteContext ctx = new TestRouteContext("GET", "/users/123");
        ctx.setPathParam("id", "123");

        dispatchBeforeChain(rm, "/users/123", ctx);
        assertTrue("Legacy route-level BEFORE must still match '/users/:id'",
                containsPrefix(sink, "beforeRoute:legacy-route:/users/123"));

        handler.handle(ctx);
        assertEquals("user#123", ctx.getBody());
    }

    private static boolean containsTarget(List<Route> routes, RouteHandler target) {
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (r != null && Objects.equals(r.getTarget(), target))
                return true;
        }
        return false;
    }

    private static int countTargets(List<Route> routes, RouteHandler target) {
        int n = 0;
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (r != null && Objects.equals(r.getTarget(), target))
                n++;
        }
        return n;
    }

    private static void assertOrder(List<Route> routes, RouteHandler... order) {
        List<RouteHandler> seen = new ArrayList<RouteHandler>();
        for (int i = 0; i < routes.size(); i++) {
            seen.add((RouteHandler) routes.get(i).getTarget());
        }
        for (int i = 0; i < order.length; i++) {
            assertSame("Order mismatch at index " + i, order[i], seen.get(i));
        }
    }

    private static int indexOfPrefix(List<String> sink, String prefix) {
        for (int i = 0; i < sink.size(); i++)
            if (sink.get(i).startsWith(prefix))
                return i;
        return -1;
    }

    private static boolean containsPrefix(List<String> sink, String prefix) {
        for (int i = 0; i < sink.size(); i++)
            if (sink.get(i).startsWith(prefix))
                return true;
        return false;
    }

    private static boolean containsOnce(List<String> sink, String exact) {
        int c = 0;
        for (int i = 0; i < sink.size(); i++)
            if (exact.equals(sink.get(i)))
                c++;
        return c == 1;
    }

    private static int countSink(List<String> sink, String exact) {
        int c = 0;
        for (int i = 0; i < sink.size(); i++)
            if (exact.equals(sink.get(i)))
                c++;
        return c;
    }
}