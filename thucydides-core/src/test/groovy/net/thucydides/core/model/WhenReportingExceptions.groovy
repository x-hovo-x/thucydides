package net.thucydides.core.model

import net.thucydides.core.PendingStepException
import net.thucydides.core.steps.StepFailureException
import net.thucydides.core.webdriver.WebdriverAssertionError
import org.junit.internal.ArrayComparisonFailure
import org.openqa.selenium.WebDriverException

import static net.thucydides.core.model.TestResult.FAILURE
import static net.thucydides.core.model.TestResult.PENDING
import static net.thucydides.core.model.TestResult.ERROR
import spock.lang.Specification
import spock.lang.Unroll

class WhenReportingExceptions extends Specification {

    def fixture = new FailureAnalysis()

    @Unroll
    def "should be able to report an exception as either a failure or an error depending on its type"() {

        when:
            def result = fixture.resultFor(exception)

        then:
            result == expectedResult

        where:
            exception                                                                               | expectedResult
            new AssertionError("test message")                                                      | FAILURE
            new ArrayComparisonFailure("test message", new AssertionError("wrapped exception"),1)   | FAILURE
            new WebdriverAssertionError(new AssertionError("wrapped assertion error"))              | FAILURE
            new StepFailureException("bother", new AssertionError("test message"))                  | FAILURE
            new StepFailureException("bother", new NoSuchElementException())                        | ERROR
            new RuntimeException("message")                                                         | ERROR
            new NullPointerException()                                                              | ERROR
            new WebDriverException()                                                                | ERROR
            new WebdriverAssertionError(new NullPointerException())                                 | ERROR
            new WebdriverAssertionError(new NoSuchElementException())                               | ERROR
            new PendingStepException("step is pending")                                             | PENDING
    }
}