package com.fl.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FlApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlApplication.class, args);
    }
}

