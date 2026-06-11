package com.fw;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WiredConsumer {

    @Autowired
    private GreetingService greetingService;

    public String consume() {
        return greetingService.greet("consumer");
    }
}
