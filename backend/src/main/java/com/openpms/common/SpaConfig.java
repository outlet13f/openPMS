package com.openpms.common;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built SPA (frontend → src/main/resources/static). Screen addresses such as /projects/web-platform
 * have no file, so they get index.html and the client router takes over; /api and /actuator are never caught.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        var file = location.createRelative(path);
                        if (file.exists() && file.isReadable()) return file;
                        if (path.startsWith("api/") || path.startsWith("actuator/") || path.contains(".")) return null;
                        var index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
