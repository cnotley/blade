package com.hellokaton.blade.server;

import com.hellokaton.blade.annotation.Path;
import com.hellokaton.blade.asm.MethodAccess;
import com.hellokaton.blade.exception.BladeException;
import com.hellokaton.blade.exception.InternalErrorException;
import com.hellokaton.blade.exception.NotFoundException;
import com.hellokaton.blade.kit.*;
import com.hellokaton.blade.mvc.HttpConst;
import com.hellokaton.blade.mvc.RouteContext;
import com.hellokaton.blade.mvc.WebContext;
import com.hellokaton.blade.mvc.handler.RequestHandler;
import com.hellokaton.blade.mvc.handler.RouteHandler;
import com.hellokaton.blade.mvc.hook.WebHook;
import com.hellokaton.blade.mvc.http.Cookie;
import com.hellokaton.blade.mvc.http.*;
import com.hellokaton.blade.mvc.route.Route;
import com.hellokaton.blade.mvc.route.RouteMatcher;
import com.hellokaton.blade.mvc.ui.ModelAndView;
import com.hellokaton.blade.mvc.ui.ResponseType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;
import lombok.var;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.hellokaton.blade.mvc.BladeConst.REQUEST_TO_STATIC_ATTR;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Http Server Handler
 *
 * @author hellokaton
 * 2022/5/9
 */
@Slf4j
public class RouteMethodHandler implements RequestHandler {

    private final RouteMatcher routeMatcher = WebContext.blade().routeMatcher();
    private final boolean hasBeforeHook = routeMatcher.hasBeforeHook();
    private final boolean hasAfterHook = routeMatcher.hasAfterHook();

    @Override
    public void handle(WebContext webContext) throws Exception {
        RouteContext context = new RouteContext(webContext.getRequest(), webContext.getResponse());

        // if execution returns false then execution is interrupted
        String uri = context.uri();
        Route route = webContext.getRoute();
        if (null == route) {
            throw new NotFoundException(context.uri());
        }

        // init route, request parameters, route action method and parameter.
        context.initRoute(route);

        // execution middleware
        List<RouteMatcher.Middleware> mds = routeMatcher.getMiddleware(context);
        if (!mds.isEmpty() && !invokeMiddleware(mds, context)) {
            return;
        }
        context.injectParameters();

        // web hook before
        if (hasBeforeHook && !invokeHook(routeMatcher.getBefore(uri), context)) {
            return;
        }

        // execute
        this.routeHandle(context);

        // webHook
        if (hasAfterHook) {
            this.invokeHook(routeMatcher.getAfter(uri), context);
        }
    }

    public HttpResponse handleResponse(WebContext webContext) {
        Request request = webContext.getRequest();
        Response response = webContext.getResponse();
        ChannelHandlerContext context = webContext.getChannelHandlerContext();

        Session session = request.session();
        if (null != session) {
            Cookie cookie = new Cookie();
            cookie.name(WebContext.blade().httpOptions().getSessionKey());
            cookie.value(session.id());
            cookie.httpOnly(true);
            cookie.secure(request.isSecure());
            response.cookie(cookie);
        }

        return response.body().write(new BodyWriter() {

            @Override
            public HttpResponse onByteBuf(ByteBuf byteBuf) {
                return createResponseByByteBuf(response, byteBuf);
            }

            @Override
            public HttpResponse onView(ViewBody body) {
                try {
                    var sw = new StringWriter();
                    WebContext.blade().templateEngine().render(body.modelAndView(), sw);
                    Objects.requireNonNull(WebContext.response())
                            .contentType(HttpConst.CONTENT_TYPE_HTML);
                    return this.onByteBuf(Unpooled.copiedBuffer(sw.toString().getBytes(StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    log.error("Render view error", e);
                }
                return null;
            }

            @Override
            public HttpResponse onStatic(StaticFileBody body) {
                request.attribute(REQUEST_TO_STATIC_ATTR, body.path());
                try {
                    HttpServerHandler.staticFileHandler.handle(webContext);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }

            @Override
            public HttpResponse onRawBody(RawBody body) {
                return body.httpResponse();
            }

            @Override
            public HttpResponse onByteBuf(String fileName, FileChannel channel) {
                var httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(response.statusCode()));

                httpResponse.headers().set(TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                setDefaultHeaders(httpResponse.headers());
                for (Map.Entry<String, String> next : response.headers().entrySet()) {
                    httpResponse.headers().set(next.getKey(), next.getValue());
                }
                if (request.keepAlive()) {
                    httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                }
                String mimeType;
                if (!httpResponse.headers().contains(HttpConst.HEADER_CONTENT_TYPE) && StringKit.isNotEmpty(fileName)) {
                    mimeType = MimeTypeKit.parse(fileName);
                    httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
                }
                long length;
                try {
                    length = channel.size();
                } catch (IOException e) {
                    log.error("Read file {} length error", fileName, e);
                    context.writeAndFlush(httpResponse);
                    return httpResponse;
                }

                httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);

                context.write(httpResponse);

                DefaultFileRegion fileRegion = new DefaultFileRegion(channel, 0, length);
                ChannelFuture sendFileFuture = context.write(fileRegion, context.newProgressivePromise());
                sendFileFuture.addListener(ProgressiveFutureListener.create(fileName, channel));
                context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                return httpResponse;
            }
        });
    }

    private void setDefaultHeaders(HttpHeaders headers) {
        headers.set(HttpHeaderNames.DATE, HttpServerInitializer.date);
        headers.set(HttpHeaderNames.SERVER, HttpConst.HEADER_SERVER_VALUE);
    }

    private FullHttpResponse createResponseByByteBuf(Response response, ByteBuf byteBuf) {
        Map<String, String> headers = response.headers();

        var httpResponse = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(response.statusCode()), byteBuf);

        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
        setDefaultHeaders(httpResponse.headers());

        if (response.cookiesRaw().size() > 0) {
            this.appendCookie(response, httpResponse);
        }

        for (Map.Entry<String, String> next : headers.entrySet()) {
            httpResponse.headers().set(next.getKey(), next.getValue());
        }
        return httpResponse;
    }

    private void appendCookie(Response response, DefaultFullHttpResponse httpResponse) {
        for (io.netty.handler.codec.http.cookie.Cookie next : response.cookiesRaw()) {
            httpResponse.headers().add(HttpHeaderNames.SET_COOKIE,
                    io.netty.handler.codec.http.cookie.ServerCookieEncoder.LAX.encode(next));
        }
    }

    /**
     * Actual routing method execution
     *
     * @param context route context
     */
    private void routeHandle(RouteContext context) {
        Object target = context.routeTarget();
        if (null == target) {
            Class<?> clazz = context.routeAction().getDeclaringClass();
            target = WebContext.blade().getBean(clazz);
        }
        if (context.targetType() == RouteHandler.class) {
            RouteHandler routeHandler = (RouteHandler) target;
            routeHandler.handle(context);
        } else {
            Method actionMethod = context.routeAction();
            Class<?> returnType = actionMethod.getReturnType();

            Path path = target.getClass().getAnnotation(Path.class);

            ResponseType responseType = ResponseType.EMPTY;
            if (null != path && !ResponseType.EMPTY.equals(path.responseType())) {
                responseType = path.responseType();
            }
            ResponseType routeResponseType = context.route().getResponseType();
            if (null != routeResponseType && !ResponseType.EMPTY.equals(routeResponseType)) {
                responseType = routeResponseType;
            }

            boolean responseJson = ResponseType.JSON.equals(responseType);
            if (responseJson) {
                if (!context.isIE()) {
                    context.contentType(HttpConst.CONTENT_TYPE_JSON);
                } else {
                    context.contentType(HttpConst.CONTENT_TYPE_HTML);
                }
            } else if (null != responseType && !ResponseType.EMPTY.equals(responseType)
                    && !ResponseType.PREVIEW.equals(responseType)) {
                context.contentType(responseType.contentType());
            }

            int len = actionMethod.getParameterTypes().length;

            MethodAccess methodAccess = BladeCache.getMethodAccess(target.getClass());

            Object[] args = len > 0 ? context.routeParameters() : null;

            Object returnParam = methodAccess.invoke(target, actionMethod.getName(), args);
            if (null == returnParam) {
                return;
            }

            if (responseJson) {
                context.json(returnParam);
                return;
            }
            if (returnType == String.class) {
                if (ResponseType.VIEW.equals(responseType)) {
                    context.body(
                            ViewBody.of(new ModelAndView(returnParam.toString()))
                    );
                } else if (ResponseType.HTML.equals(responseType)) {
                    context.html(returnParam.toString());
                } else if (ResponseType.TEXT.equals(responseType)) {
                    context.text(returnParam.toString());
                }
                return;
            }
            if (returnType == ModelAndView.class) {
                context.body(
                        ViewBody.of((ModelAndView) returnParam)
                );
            }
            if (returnType == StaticFileBody.class) {
                context.body((StaticFileBody) returnParam);
            }
        }
    }

    /**
     * Invoke WebHook
     *
     * @param context   current execute route handler signature
     * @param hookRoute current webhook route handler
     * @return Return true then next handler, and else interrupt request
     * @throws Exception throw like parse param exception
     */
    private boolean invokeHook(RouteContext context, Route hookRoute) throws Exception {
        Method hookMethod = hookRoute.getAction();
        Object target = WebContext.blade().ioc().getBean(hookRoute.getTargetType());
        if (null == target) {
            Class<?> clazz = hookRoute.getAction().getDeclaringClass();
            target = WebContext.blade().ioc().getBean(clazz);
            hookRoute.setTarget(target);
        }

        // execute
        int len = hookMethod.getParameterTypes().length;
        hookMethod.setAccessible(true);

        Object returnParam;
        if (len > 0) {
            if (len == 1) {
                MethodAccess methodAccess = BladeCache.getMethodAccess(target.getClass());
                returnParam = methodAccess.invoke(target, hookMethod.getName(), context);
            } else if (len == 2) {
                MethodAccess methodAccess = BladeCache.getMethodAccess(target.getClass());

                returnParam = methodAccess.invoke(target, hookMethod.getName(),
                        context.request(), context.response());
            } else {
                throw new InternalErrorException("Bad web hook structure");
            }
        } else {
            returnParam = ReflectKit.invokeMethod(target, hookMethod);
        }

        if (null == returnParam) return true;

        Class<?> returnType = returnParam.getClass();
        if (returnType == Boolean.class) {
            return Boolean.parseBoolean(returnParam.toString());
        }
        return true;
    }

    private boolean invokeMiddleware(List<RouteMatcher.Middleware> middleware, RouteContext context) throws BladeException {
        if (BladeKit.isEmpty(middleware)) {
            return true;
        }
        for (RouteMatcher.Middleware m : middleware) {
            try {
                boolean flag = m.getHook().before(context);
                if (!flag) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("SelectiveMiddleware: {}", e.getMessage());
            }
        }
        return true;
    }

    /**
     * invoke hooks
     *
     * @param hooks   webHook list
     * @param context http request
     * @return return invoke hook is abort
     */
    private boolean invokeHook(List<Route> hooks, RouteContext context) throws Exception {
        for (Route hook : hooks) {
            if (hook.getTargetType() == RouteHandler.class) {
                RouteHandler routeHandler = (RouteHandler) hook.getTarget();
                routeHandler.handle(context);
                if (context.isAbort()) {
                    return false;
                }
            } else {
                boolean flag = this.invokeHook(context, hook);
                if (!flag) return false;
            }
        }
        return true;
    }

}