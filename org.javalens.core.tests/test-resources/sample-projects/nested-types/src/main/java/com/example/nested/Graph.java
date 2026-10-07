package com.example.nested;

import java.util.Map.Entry;
import com.example.util.*;
import static com.example.util.Tools.trim;

public class Graph extends Shelf implements Runnable {

    Entry<String, Integer> entry;
    Tools tools;
    Shelf.Folio folio;

    public void run() {
        trim("graph");
    }
}
