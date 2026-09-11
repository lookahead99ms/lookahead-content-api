package com.lookahead.learning.content.filter;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;

class OAuthStorageFailureFilterTest {
    @Test void databaseFailuresBeforeMvcReturnUnavailableInsteadOfSignIn() throws Exception {
        for (var failure : new RuntimeException[]{new DataAccessResourceFailureException("private database details"),
                new CannotCreateTransactionException("private connection details")}) {
            var response = new MockHttpServletResponse();
            new OAuthStorageFailureFilter().doFilter(new MockHttpServletRequest("GET", "/content/example.json"), response,
                    (request, result) -> { throw failure; });
            assertEquals(503, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertTrue(response.getContentAsString().contains("ACCOUNT_STORAGE_UNAVAILABLE"));
            assertFalse(response.getContentAsString().contains("private"));
        }
    }
    @Test void unrelatedFailuresAreNotReclassified() {
        assertThrows(IllegalArgumentException.class, () -> new OAuthStorageFailureFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> { throw new IllegalArgumentException("not storage"); }));
    }
}
