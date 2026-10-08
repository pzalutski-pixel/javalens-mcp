package com.deadline;

import java.util.ArrayList;
import java.util.List;

/** Frame is a nested type to move to its own file; titles() has an index loop to convert. */
public class Gallery {

    public static class Frame {
        public String title() {
            return "frame";
        }
    }

    private final List<Frame> frames = new ArrayList<>();

    public String banner() {
        return "gallery";
    }

    public int titles() {
        int length = 0;
        for (int i = 0; i < frames.size(); i++) {
            length += frames.get(i).title().length();
        }
        return length;
    }
}
