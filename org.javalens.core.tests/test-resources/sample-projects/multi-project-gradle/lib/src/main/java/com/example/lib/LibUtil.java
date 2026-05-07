package com.example.lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibUtil {
    private static final Logger log = LoggerFactory.getLogger(LibUtil.class);

    public String tag(String s) {
        log.debug("tagging {}", s);
        return "[" + s + "]";
    }
}
