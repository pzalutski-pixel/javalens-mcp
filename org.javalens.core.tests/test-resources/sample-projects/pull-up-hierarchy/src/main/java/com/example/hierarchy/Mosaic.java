package com.example.hierarchy;

public class Mosaic {

    static class Tile {
        int span() {
            return 0;
        }

        int grout() {
            return 0;
        }
    }

    Object[] pieces() {
        Object first;
        {
            class Echo extends Tile {
                int glaze() {
                    return 1;
                }
            }
            first = new Echo();
        }
        Object second;
        {
            class Echo {
            }
            second = new Echo();
        }
        return new Object[] { first, second };
    }

    static class Slate extends Tile {
        int veneer() {
            return 2;
        }
    }
}
