package uk.gov.defra.trade.imports.animals.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the caller-identity plumbing added to {@link RequestTracingFilter}: the {@code
 * Trade-Imports-Crn} request header is stashed into MDC under {@code crn} for the duration of the
 * request (and cleared afterwards), and an absent header is tolerated rather than rejected.
 */
class RequestTracingFilterCrnTest {

  private static final String CRN_HEADER = "Trade-Imports-Crn";

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  private RequestTracingFilter filter() {
    RequestTracingFilter filter = new RequestTracingFilter();
    ReflectionTestUtils.setField(filter, "header", "x-cdp-request-id");
    return filter;
  }

  @Test
  void stashesCrnIntoMdc_whenHeaderPresent() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CRN_HEADER, "GBCRN123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> crnDuringChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> crnDuringChain.set(MDC.get("crn"));

    filter().doFilter(request, response, chain);

    assertThat(crnDuringChain.get()).isEqualTo("GBCRN123");
    assertThat(MDC.get("crn")).isNull();
  }

  @Test
  void toleratesAbsentCrnHeader() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> crnDuringChain = new AtomicReference<>();
    AtomicReference<Boolean> chainInvoked = new AtomicReference<>(false);
    FilterChain chain =
        (req, res) -> {
          chainInvoked.set(true);
          crnDuringChain.set(MDC.get("crn"));
        };

    filter().doFilter(request, response, chain);

    assertThat(chainInvoked.get()).isTrue();
    assertThat(crnDuringChain.get()).isNull();
  }
}
