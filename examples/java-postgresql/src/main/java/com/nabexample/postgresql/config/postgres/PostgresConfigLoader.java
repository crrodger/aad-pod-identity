package com.nabexample.postgresql.config.postgres;

import java.io.IOException;

import com.microsoft.azure.msiAuthTokenProvider.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.sql.Types;
import java.sql.Timestamp;

import javax.validation.constraints.Null;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Order(Ordered.LOWEST_PRECEDENCE)
public class PostgresConfigLoader implements EnvironmentPostProcessor {

    private static Logger logger = LoggerFactory.getLogger(PostgresConfigLoader.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
       return;
    }
    
}
