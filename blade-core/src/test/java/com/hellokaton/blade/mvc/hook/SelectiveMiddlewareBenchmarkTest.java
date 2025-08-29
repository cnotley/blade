package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.http.HttpMethod;
import com.hellokaton.blade.mvc.route.Route;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SelectiveMiddlewareBenchmarkTest {

    @Test
    public void benchmark() throws Exception {
        RouteMatcher matcher = new RouteMatcher();
        List<Route> legacy = new ArrayList<>();
        RouteHandler handler = ctx -> {};
        for (int i = 0; i < 10; i++) {
            Route r = matcher.addRoute("/t" + i, handler, HttpMethod.BEFORE);
            legacy.add(new Route(r));
        }
        String path = "/t1";

        legacyGetBefore(legacy, path);
        matcher.getBefore(path);

        int threads = 4;
        int iterations = 2000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        long startLegacy = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    legacyGetBefore(legacy, path);
                }
            });
        }
        es.shutdown();
        es.awaitTermination(1, TimeUnit.MINUTES);
        long timeLegacy = System.nanoTime() - startLegacy;

        ExecutorService es2 = Executors.newFixedThreadPool(threads);
        long startNew = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            es2.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    matcher.getBefore(path);
                }
            });
        }
        es2.shutdown();
        es2.awaitTermination(1, TimeUnit.MINUTES);
        long timeNew = System.nanoTime() - startNew;

        double overhead = (double) (timeNew - timeLegacy) / (double) timeLegacy;
        System.out.println("legacy=" + timeLegacy + " new=" + timeNew + " overhead=" + overhead);
    }

    private static List<Route> legacyGetBefore(List<Route> hooks, String path) {
        String cleanPath = parsePath(path);
        return hooks.stream()
                .sorted(Comparator.comparingInt(Route::getSort))
                .filter(route -> route.getHttpMethod() == HttpMethod.BEFORE)
                .filter(route -> cleanPath.startsWith(route.getRewritePath()))
                .collect(Collectors.toList());
    }

    private static String parsePath(String path) {
        try {
            URI uri = new URI(path);
            return uri.getPath();
        } catch (URISyntaxException e) {
            return path;
        }
    }
}
