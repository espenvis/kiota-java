package com.microsoft.kiota.authentication;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Implementation of AccessTokenProvider that supports implementations of TokenCredential from Azure.Identity. */
public class AzureIdentityAccessTokenProvider implements AccessTokenProvider {
    private final TokenCredential creds;
    private final List<String> _scopes;
    private final AllowedHostsValidator _hostValidator;
    private final ObservabilityOptions _observabilityOptions;
    private final boolean _isCaeEnabled;
    private static final HashSet<String> localhostStrings =
            new HashSet<>(Arrays.asList("localhost", "[::1]", "::1", "127.0.0.1"));

    /**
     * Creates a new instance of AzureIdentityAccessTokenProvider.
     * @param tokenCredential The Azure.Identity.TokenCredential implementation to use.
     * @param allowedHosts The list of allowed hosts for which to request access tokens.
     * @param scopes The scopes to request access tokens for.
     */
    @SuppressWarnings("LambdaLast")
    public AzureIdentityAccessTokenProvider(
            @Nonnull final TokenCredential tokenCredential,
            @Nonnull final String[] allowedHosts,
            @Nonnull final String... scopes) {
        this(tokenCredential, allowedHosts, null, scopes);
    }

    /**
     * Creates a new instance of AzureIdentityAccessTokenProvider.
     * @param tokenCredential The Azure.Identity.TokenCredential implementation to use.
     * @param allowedHosts The list of allowed hosts for which to request access tokens.
     * @param observabilityOptions The observability options to use.
     * @param scopes The scopes to request access tokens for.
     */
    @SuppressWarnings("LambdaLast")
    public AzureIdentityAccessTokenProvider(
            @Nonnull final TokenCredential tokenCredential,
            @Nonnull final String[] allowedHosts,
            @Nullable final ObservabilityOptions observabilityOptions,
            @Nonnull final String... scopes) {
        this(tokenCredential, allowedHosts, observabilityOptions, true, scopes);
    }

    /**
     * Creates a new instance of AzureIdentityAccessTokenProvider.
     * @param tokenCredential The Azure.Identity.TokenCredential implementation to use.
     * @param allowedHosts The list of allowed hosts for which to request access tokens.
     * @param observabilityOptions The observability options to use.
     * @param isCaeEnabled A flag to enable or disable the Continuous Access Evaluation.
     * @param scopes The scopes to request access tokens for.
     */
    @SuppressWarnings("LambdaLast")
    public AzureIdentityAccessTokenProvider(
            @Nonnull final TokenCredential tokenCredential,
            @Nonnull final String[] allowedHosts,
            @Nullable final ObservabilityOptions observabilityOptions,
            final boolean isCaeEnabled,
            @Nonnull final String... scopes) {
        creds = Objects.requireNonNull(tokenCredential, "parameter tokenCredential cannot be null");

        if (scopes == null) {
            _scopes = new ArrayList<String>();
        } else {
            _scopes = new ArrayList<>(Arrays.asList(scopes));
        }
        if (allowedHosts == null || allowedHosts.length == 0) {
            _hostValidator = new AllowedHostsValidator();
        } else {
            _hostValidator = new AllowedHostsValidator(allowedHosts);
        }
        if (observabilityOptions == null) {
            _observabilityOptions = new ObservabilityOptions();
        } else {
            _observabilityOptions = observabilityOptions;
        }
        _isCaeEnabled = isCaeEnabled;
    }

    private static final String ClaimsKey = "claims";
    private static final String parentSpanKey = "parent-span";

    @Nonnull public String getAuthorizationToken(
            @Nonnull final URI uri,
            @Nullable final Map<String, Object> additionalAuthenticationContext) {
        Span span;
        if (additionalAuthenticationContext != null
                && additionalAuthenticationContext.containsKey(parentSpanKey)
                && additionalAuthenticationContext.get(parentSpanKey) instanceof Span) {
            final Span parentSpan = (Span) additionalAuthenticationContext.get(parentSpanKey);
            span =
                    GlobalOpenTelemetry.getTracer(
                                    _observabilityOptions.getTracerInstrumentationName())
                            .spanBuilder("getAuthorizationToken")
                            .setParent(Context.current().with(parentSpan))
                            .startSpan();
        } else {
            span =
                    GlobalOpenTelemetry.getTracer(
                                    _observabilityOptions.getTracerInstrumentationName())
                            .spanBuilder("getAuthorizationToken")
                            .startSpan();
        }
        try (final Scope scope = span.makeCurrent()) {
            if (!_hostValidator.isUrlHostValid(uri)) {
                span.setAttribute("com.microsoft.kiota.authentication.is_url_valid", false);
                return "";
            }
            if (!uri.getScheme().equalsIgnoreCase("https") && !isLocalhostUrl(uri.getHost())) {
                span.setAttribute("com.microsoft.kiota.authentication.is_url_valid", false);
                throw new IllegalArgumentException("Only https is supported");
            }
            span.setAttribute("com.microsoft.kiota.authentication.is_url_valid", true);

            String decodedClaim = null;

            if (additionalAuthenticationContext != null
                    && additionalAuthenticationContext.containsKey(ClaimsKey)
                    && additionalAuthenticationContext.get(ClaimsKey) instanceof String) {
                final String rawClaim = (String) additionalAuthenticationContext.get(ClaimsKey);
                try {
                    decodedClaim = new String(Base64.getDecoder().decode(rawClaim), "UTF-8");
                } catch (final UnsupportedEncodingException e) {
                    span.recordException(e);
                }
            }
            span.setAttribute(
                    "com.microsoft.kiota.authentication.additional_claims_provided",
                    decodedClaim != null && !decodedClaim.isEmpty());

            List<String> scopes;
            if (!_scopes.isEmpty()) {
                scopes = new ArrayList<>(_scopes);
            } else {
                scopes = new ArrayList<>();
                scopes.add(uri.getScheme() + "://" + uri.getHost() + "/.default");
            }

            final TokenRequestContext context = new TokenRequestContext();
            context.setScopes(scopes);
            context.setCaeEnabled(_isCaeEnabled);
            span.setAttribute(
                    "com.microsoft.kiota.authentication.scopes", String.join("|", scopes));
            if (decodedClaim != null && !decodedClaim.isEmpty()) {
                context.setClaims(decodedClaim);
            }
            return this.creds.getTokenSync(context).getToken();
        } catch (IllegalArgumentException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Nonnull public AllowedHostsValidator getAllowedHostsValidator() {
        return _hostValidator;
    }

    private static boolean isLocalhostUrl(@Nonnull String host) {
        Objects.requireNonNull(host);
        return localhostStrings.contains(host.toLowerCase(Locale.ROOT));
    }
}
