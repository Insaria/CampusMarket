package edu.bistu.campusmarket.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiAccess {
    Requirement value();

    enum Requirement {
        PUBLIC, AUTHENTICATED, USER, ADMIN, MERCHANT, SELLER_HISTORY
    }
}
