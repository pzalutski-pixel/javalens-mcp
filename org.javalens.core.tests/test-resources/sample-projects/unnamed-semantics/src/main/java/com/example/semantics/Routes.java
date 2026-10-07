package com.example.semantics;

import java.util.function.Supplier;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/routes")
public class Routes {

    Object fallback() {
        return new Object() {
            @GET
            @Path("/hidden")
            public String hidden() {
                return "hidden";
            }
        };
    }

    Supplier<Object> deferred() {
        return () -> new Object() {
            @GET
            @Path("/later")
            public String later() {
                return "later";
            }
        };
    }

    @GET
    @Path("/list")
    public String list() {
        return "list";
    }

    @GET
    @Path("/count")
    public String count() {
        return "count";
    }

    @POST
    @Path("/add")
    public String add() {
        return "add";
    }

    Object scoped() {
        @Path("/scoped")
        class Scoped {
            @GET
            @Path("/inside")
            public String inside() {
                return "inside";
            }
        }
        return new Scoped();
    }

    @Path("/inner")
    public class Inner {
        @GET
        @Path("/nested")
        public String nested() {
            return "nested";
        }
    }

    @Path("/ledger")
    public record Ledger(String id) {
        @GET
        @Path("/entry")
        public String entry() {
            return id;
        }
    }

    @Path("/level")
    public enum Level {
        LOW;

        @POST
        @Path("/current")
        public String current() {
            return name();
        }
    }

    @Path("/archive")
    public static class Archive {
        @GET
        @Path("/all")
        public String all() {
            return "all";
        }

        @POST
        @Path("/store")
        public String store() {
            return "store";
        }
    }

    @Path("/outer")
    public class Holder {
        @Path("/deep")
        public static class Deep {
            @GET
            @Path("/leaf")
            public String leaf() {
                return "leaf";
            }
        }
    }
}
