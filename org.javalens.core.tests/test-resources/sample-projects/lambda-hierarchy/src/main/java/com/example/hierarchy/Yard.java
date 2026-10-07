package com.example.hierarchy;

import java.util.List;
import java.util.function.Supplier;

public class Yard {

    static class Brick {
        int heft() {
            return 1;
        }
    }

    Supplier<Brick> bricks() {
        return () -> new Brick() {
        };
    }

    static class Beam {
        int load() {
            return 0;
        }
    }

    List<Beam> beams() {
        class Joist extends Beam {
        }
        class Rafter extends Beam {
        }
        return List.of(new Joist(), new Rafter());
    }

    static class Slab {
        int depth() {
            return 0;
        }
    }

    List<Slab> slabs() {
        Supplier<Slab> deferred = () -> {
            class Wet extends Slab {
                int wetness() {
                    return 1;
                }
            }
            return new Wet();
        };
        class Dry extends Slab {
            int grade() {
                return 2;
            }
        }
        return List.of(deferred.get(), new Dry());
    }

    static class Post {
    }

    Object posts() {
        class Pillar extends Post {
            int rise() {
                return 1;
            }
        }
        return new Pillar();
    }
}
