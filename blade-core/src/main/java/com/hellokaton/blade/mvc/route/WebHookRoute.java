package com.hellokaton.blade.mvc.route;

import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.hook.WebHookOptions;
import com.hellokaton.blade.mvc.http.HttpMethod;

import java.lang.reflect.Method;

/**
 * Route wrapper for WebHook with options and order.
 */
public class WebHookRoute extends Route {

    private final WebHook webHook;
    private final WebHookOptions options;
    private final int order;

    public WebHookRoute(HttpMethod httpMethod, String path, WebHook webHook,
                        Class<?> targetType, Method action, WebHookOptions options, int order) {
        super(httpMethod, path, webHook, targetType, action, null);
        this.webHook = webHook;
        this.options = options;
        this.order = order;
    }

    public WebHook getWebHook() {
        return webHook;
    }

    public WebHookOptions getOptions() {
        return options;
    }

    public int getOrder() {
        return order;
    }
}

