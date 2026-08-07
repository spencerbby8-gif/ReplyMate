package com.replymate.core.auth;

/** Platform-neutral HTTPS transport for Supabase Auth REST calls. The core client
 *  never touches sockets (BLUEPRINT §1 layering); the app layer provides the wire.
 *  Implementations add the apikey + Authorization headers and the base URL. */
public interface AuthTransport {

    final class Response {
        public final int code;
        public final String body;
        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /** @param method  HTTP method (GET/POST/PUT/DELETE)
     *  @param path    path with query, relative to the project URL ("/auth/v1/otp")
     *  @param jsonBody request JSON (may be empty)
     *  @param bearerJwt user access token for authenticated calls ("" = none)
     *  @return response; never throws for HTTP errors (code carries them).
     *          Throws nothing — transport failures come back as code -1 bodies. */
    Response call(String method, String path, String jsonBody, String bearerJwt);
}
