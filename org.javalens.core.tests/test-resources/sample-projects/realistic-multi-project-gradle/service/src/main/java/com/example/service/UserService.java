package com.example.service;

import com.example.model.User;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    public String formatGreeting(User user) {
        log.debug("formatting greeting for {}", user);
        if (user == null || StringUtils.isBlank(user.getName())) {
            return "Hello, anonymous!";
        }
        return "Hello, " + StringUtils.capitalize(user.getName()) + "!";
    }

    public boolean isAdult(User user) {
        return user != null && user.getAge() >= 18;
    }
}
