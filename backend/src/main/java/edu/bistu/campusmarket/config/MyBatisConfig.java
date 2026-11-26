package edu.bistu.campusmarket.config;

import edu.bistu.campusmarket.mapper.UserAccountMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("db")
@MapperScan(basePackageClasses = UserAccountMapper.class)
public class MyBatisConfig {
}
