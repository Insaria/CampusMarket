package edu.bistu.campusmarket.config;

import edu.bistu.campusmarket.security.ApiAccessInterceptor;
import edu.bistu.campusmarket.security.CsrfInterceptor;
import edu.bistu.campusmarket.security.CurrentUserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("db")
public class ApiAccessConfig implements WebMvcConfigurer {
    private final CurrentUserService currentUserService;

    public ApiAccessConfig(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CsrfInterceptor()).order(-1);
        registry.addInterceptor(new ApiAccessInterceptor(currentUserService));
    }
}
