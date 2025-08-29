package com.hellokaton.blade.mvc.hook;

import com.hellokaton.blade.mvc.RouteContext;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Internal holder for {@link WebHook} with options and registration order.
 */
@Getter
@AllArgsConstructor
public class WebHookWrapper {

    private final WebHook hook;
    private final WebHookOptions options;
    private final int order;

    public boolean matches(RouteContext ctx) {
        String path = WebHookOptions.normalizePath(ctx.uri());
        String method = ctx.method() == null ? null : ctx.method().toUpperCase(Locale.ROOT);

        boolean included = options.getIncludes().isEmpty() || options.getIncludes().stream().anyMatch(p -> p.matcher(path).matches());
        if (!included) {
            return false;
        }
        boolean excluded = options.getExcludes().stream().anyMatch(p -> p.matcher(path).matches());
        if (excluded) {
            return false;
        }
        if (!options.getMethods().isEmpty() && (method == null || !options.getMethods().contains(method))) {
            return false;
        }
        try {
            return options.getPredicate().test(ctx);
        } catch (Exception e) {
            // logging handled by caller
            return false;
        }
    }

    public static final Comparator<WebHookWrapper> ORDERING = Comparator
            .comparingInt((WebHookWrapper w) -> w.getOptions().getPriority())
            .thenComparingInt(WebHookWrapper::getOrder);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebHookWrapper)) return false;
        WebHookWrapper that = (WebHookWrapper) o;
        return order == that.order && hook == that.hook && Objects.equals(options, that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hook, options, order);
    }
}
