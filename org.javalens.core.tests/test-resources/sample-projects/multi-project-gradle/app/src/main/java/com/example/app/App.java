package com.example.app;

import com.example.lib.LibUtil;
import org.apache.commons.lang3.StringUtils;

public class App {
    private final LibUtil util = new LibUtil();

    public String run(String input) {
        return util.tag(StringUtils.reverse(input));
    }
}
