package com.fw;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/legacy")
public class LegacyResource {

    @GET
    @Path("/ping")
    public String ping() {
        return "pong";
    }

    @POST
    public String submit() {
        return "accepted";
    }
}
