package com.lookahead.learning.content.atdd;

import com.lookahead.learning.content.handler.AccountErrorHandler;
import com.lookahead.learning.content.handler.GlobalExceptionHandler;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Exercises production exception advice; real transaction/driver behavior remains in JUnit/live checks. */
public class StorageErrorSteps {
    private RuntimeException failure;
    private MvcResult result;

    @Given("storage fails during {string}")
    public void fail(String stage) {
        var cause = new SQLException("synthetic private driver detail", "08006");
        failure = switch (stage) {
            case "connection acquisition" -> new CannotCreateTransactionException("synthetic acquisition", cause);
            case "query" -> new DataAccessResourceFailureException("synthetic query", cause);
            case "rollback", "commit" -> new TransactionSystemException("synthetic " + stage, cause);
            case "unrelated programming error" -> new IllegalStateException("synthetic programmer detail");
            default -> throw new IllegalArgumentException("Unknown failure stage");
        };
    }
    @When("an account request reaches the error handler")
    public void request() throws Exception {
        result = MockMvcBuilders.standaloneSetup(new FailureEndpoint(failure))
                .setControllerAdvice(new AccountErrorHandler(), new GlobalExceptionHandler()).build()
                .perform(post("/atdd/account-operation")).andReturn();
    }
    @Then("the storage response status is {int}")
    public void status(int expected) { assertThat(result.getResponse().getStatus()).isEqualTo(expected); }
    @Then("the storage response code is {string}")
    public void code(String expected) throws Exception {
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + expected + "\"");
    }
    @Then("the storage response is private and contains no driver details")
    public void safe() throws Exception {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic", "SQLException", "08006")
                .contains("retain your draft", "retry the same request");
    }
    @Then("the response does not claim a retryable storage outage")
    public void unrelated() throws Exception {
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ACCOUNT_STORAGE_UNAVAILABLE", "synthetic");
    }
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class FailureEndpoint {
        private final RuntimeException failure;
        FailureEndpoint(RuntimeException failure) { this.failure = failure; }
        @PostMapping("/atdd/account-operation")
        public void execute() { throw failure; }
    }
}
