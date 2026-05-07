package uk.gov.defra.trade.imports.animals.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Checks whether a candidate redirect URL shares the same origin (scheme, host, port) as a
 * configured base URL. Used to prevent open-redirect attacks against the document-upload
 * redirectUrl parameter.
 *
 * <p>String-prefix matching is bypassable — e.g. {@code "http://localhost:3000.evil.com"} starts
 * with {@code "http://localhost:3000"} — so the comparison must decompose both URLs and check
 * each origin component independently.
 *
 * <p>Comparison is case-insensitive for scheme and host. Ports are normalised to their scheme
 * defaults (80 for http, 443 for https) so {@code "http://example.com"} and
 * {@code "http://example.com:80"} match.
 */
public final class RedirectOriginChecker {

  private RedirectOriginChecker() {}

  public static boolean matches(String candidateUrl, String expectedBaseUrl) {
    try {
      URI candidate = new URI(candidateUrl);
      URI expected = new URI(expectedBaseUrl);
      if (candidate.getScheme() == null || candidate.getHost() == null
          || expected.getScheme() == null || expected.getHost() == null) {
        return false;
      }
      return candidate.getScheme().equalsIgnoreCase(expected.getScheme())
          && candidate.getHost().equalsIgnoreCase(expected.getHost())
          && Objects.equals(normalisePort(candidate), normalisePort(expected));
    } catch (URISyntaxException _) {
      return false;
    }
  }

  private static int normalisePort(URI uri) {
    int port = uri.getPort();
    if (port != -1) {
      return port;
    }
    String scheme = uri.getScheme();
    if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    }
    if ("http".equalsIgnoreCase(scheme)) {
      return 80;
    }
    return -1;
  }
}
