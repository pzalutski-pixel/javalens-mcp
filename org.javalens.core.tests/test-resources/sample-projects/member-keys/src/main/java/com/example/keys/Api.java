package com.example.keys;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

public class Api {

    @Path("/orders")
    public static class Orders {

        int served;

        String note;

        @GET
        @Path("/open")
        public String open() {
            served++;
            return "open";
        }

        String closed() {
            return note;
        }
    }

    static class Archive {
    }
}
