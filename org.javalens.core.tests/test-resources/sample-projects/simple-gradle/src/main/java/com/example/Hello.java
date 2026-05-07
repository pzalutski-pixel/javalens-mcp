package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hello {
    private static final Logger log = LoggerFactory.getLogger(Hello.class);

    public String greet(String name) {
        log.info("greeting {}", name);
        return "hello, " + name;
    }
}
