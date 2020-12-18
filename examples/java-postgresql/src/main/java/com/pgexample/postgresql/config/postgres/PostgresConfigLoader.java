package com.pgexample.postgresql.config.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

@Order(Ordered.LOWEST_PRECEDENCE)
public class PostgresConfigLoader implements EnvironmentPostProcessor {

    private static Logger logger = LoggerFactory.getLogger(PostgresConfigLoader.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
       return;
    }
    
}
