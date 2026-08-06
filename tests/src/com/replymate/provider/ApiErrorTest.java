package com.replymate.provider;

import com.replymate.provider.http.ApiError;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiErrorTest {

    @Test public void classifyStatusCodes() {
        assertEquals(ApiError.Type.AUTH, ApiError.classify(401));
        assertEquals(ApiError.Type.AUTH, ApiError.classify(403));
        assertEquals(ApiError.Type.QUOTA, ApiError.classify(429));
        assertEquals(ApiError.Type.SERVER, ApiError.classify(500));
        assertEquals(ApiError.Type.SERVER, ApiError.classify(503));
        assertEquals(ApiError.Type.NETWORK, ApiError.classify(-1));
        assertEquals(ApiError.Type.UNKNOWN, ApiError.classify(404));
        assertEquals(ApiError.Type.UNKNOWN, ApiError.classify(400));
    }

    @Test public void retryableMatchesPolicyRules() {
        assertTrue(new ApiError(ApiError.Type.QUOTA, "", -1).retryable());
        assertFalse(new ApiError(ApiError.Type.AUTH, "", -1).retryable());
    }

    @Test public void parsesGoogleRetryDelay() {
        String body429 = "{\"error\":{\"code\":429,\"message\":\"quota\",\"details\":["
            + "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"17s\"}]}}";
        ApiError e = ApiError.of(429, body429);
        assertEquals(ApiError.Type.QUOTA, e.type);
        assertEquals(17, e.retryAfterSeconds);
    }

    @Test public void missingRetryDelayYieldsUnknown() {
        assertEquals(-1, ApiError.of(429, "{\"error\":{\"code\":429}}").retryAfterSeconds);
        assertEquals(-1, ApiError.of(429, null).retryAfterSeconds);
    }

    @Test public void userCopyIsSpecific() {
        assertTrue(ApiError.defaultMessage(ApiError.Type.AUTH).toLowerCase().contains("api key"));
        assertTrue(ApiError.defaultMessage(ApiError.Type.NETWORK).toLowerCase().contains("connection"));
    }
}
