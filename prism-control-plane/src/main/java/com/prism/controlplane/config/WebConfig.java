package com.prism.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.seed.SeedLoader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ControlPlaneProperties props;

    public WebConfig(ControlPlaneProperties props) {
        this.props = props;
    }

    /** Same idempotent seed as the gateway, so either service can start first on an empty database. */
    @Bean
    SeedLoader.Summary seedData(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new SeedLoader(jdbc, mapper).load(props.dataDir());
    }

    /** Every /admin/** endpoint requires the admin token. The console page itself is public; its API calls are not. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                String auth = request.getHeader("Authorization");
                if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)
                        && MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8),
                        props.adminToken().getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"message\":\"Admin token required: 'Authorization: Bearer <admin token>'\","
                        + "\"type\":\"authentication_error\"}}");
                return false;
            }
        }).addPathPatterns("/admin/**");
    }
}
