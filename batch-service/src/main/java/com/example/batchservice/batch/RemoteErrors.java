package com.example.batchservice.batch;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

final class RemoteErrors {

    private RemoteErrors() {
    }

    static String describe(String service, Exception e) {
        if (e instanceof RestClientResponseException r) {
            String body = r.getResponseBodyAsString();
            return service + " responded " + r.getStatusCode().value()
                    + (body.isBlank() ? "" : ": " + body);
        }
        if (e instanceof ResourceAccessException) {
            return service + " unreachable: " + e.getMessage();
        }
        return service + " call failed: " + e;
    }
}
