package com.hellokaton.blade.mvc.route;

import com.hellokaton.blade.mvc.hook.WebHookOptions;

/**
 * Internal structure to associate a hook route with its registration options.
 * This is used to drive selection and ordering of before/after hooks and global middleware.
 */
public class HookEntry {

    private final Route route;
    private WebHookOptions options;

    public HookEntry(Route route, WebHookOptions options) {
        this.route = route;
        this.options = options;
    }

    public Route getRoute() {
        return route;
    }

    public WebHookOptions getOptions() {
        return options;
    }

    public void setOptions(WebHookOptions options) {
        this.options = options;
    }
}
