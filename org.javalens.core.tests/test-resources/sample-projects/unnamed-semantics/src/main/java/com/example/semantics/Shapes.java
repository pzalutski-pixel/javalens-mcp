package com.example.semantics;

public class Shapes {

    static class Figure {
        int corners;

        int sides() {
            return corners;
        }
    }

    static class Square extends Figure {
    }

    static class Plain {
        int weight() {
            return 1;
        }
    }

    Figure triangle() {
        return new Figure() {
        };
    }

    Figure circle() {
        return new Figure() {
        };
    }

    Plain light() {
        return new Plain() {
        };
    }

    Board board() {
        return new Board() {
        };
    }
}
