package io.meridian.filegateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * SEC-36: absolute URLs of pre-signed download links come from {@code meridian.files.public-base-url}, never from
 * the request's Host header (a poisoned Host would hand out links pointing elsewhere). Without a configured base the
 * link stays relative, except in dev mode where the request address is used for local convenience.
 */
@Component
public class LinkBase {
    private final String base;
    private final boolean dev;

    public LinkBase(@Value("${meridian.files.public-base-url:}") String base, Environment env) {
        this.base = base == null ? "" : base.trim().replaceAll("/+$", "");
        this.dev = io.meridian.web.security.RequiredSecrets.devMode(env);
    }

    public String absolute(String path) {
        if (!base.isEmpty()) {
            return base + path;
        }
        if (dev && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest r = a.getRequest();
            return r.getScheme() + "://" + r.getServerName() + ":" + r.getServerPort() + r.getContextPath() + path;
        }
        return path;
    }
}
