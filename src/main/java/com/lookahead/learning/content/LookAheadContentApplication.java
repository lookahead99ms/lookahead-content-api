package com.lookahead.learning.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
public class LookAheadContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LookAheadContentApplication.class, args);
    }
}
