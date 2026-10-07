package org.bugs;

import java.io.FileInputStream;
import java.io.InputStream;

public class Streams {

    int first(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        return in.read();
    }

    void quiet(String text) {
        try {
            Integer.parseInt(text);
        } catch (NumberFormatException e) {
        }
    }
}
