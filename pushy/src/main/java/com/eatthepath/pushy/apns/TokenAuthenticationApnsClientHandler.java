/*
 * Copyright (c) 2020 Jon Chambers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.eatthepath.pushy.apns;

import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.auth.AuthenticationToken;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

class TokenAuthenticationApnsClientHandler extends ApnsClientHandler {

    private final ApnsSigningKey signingKey;

    private AuthenticationToken authenticationToken;
    private int mostRecentStreamWithNewToken = 0;

    private final Duration tokenExpiration;
    private ScheduledFuture<?> expireTokenFuture;

    private static final AsciiString APNS_AUTHORIZATION_HEADER = new AsciiString("authorization");

    private static final String EXPIRED_AUTH_TOKEN_REASON = "ExpiredProviderToken";

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationApnsClientHandler.class);

    public static class TokenAuthenticationApnsClientHandlerBuilder extends ApnsClientHandlerBuilder {
        private ApnsSigningKey signingKey;
        private Duration tokenExpiration;

        public TokenAuthenticationApnsClientHandlerBuilder signingKey(final ApnsSigningKey signingKey) {
            this.signingKey = signingKey;
            return this;
        }

        public ApnsSigningKey signingKey() {
            return this.signingKey;
        }

        public TokenAuthenticationApnsClientHandlerBuilder tokenExpiration(final Duration tokenExpiration) {
            this.tokenExpiration = tokenExpiration;
            return this;
        }

        public Duration tokenExpiration() {
            return this.tokenExpiration;
        }

        @Override
        public ApnsClientHandler build(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings) {
            Objects.requireNonNull(this.authority(), "Authority must be set before building a TokenAuthenticationApnsClientHandler.");
            Objects.requireNonNull(this.signingKey(), "Signing key must be set before building a TokenAuthenticationApnsClientHandler.");
            Objects.requireNonNull(this.tokenExpiration(), "Token expiration duration must be set before building a TokenAuthenticationApnsClientHandler.");

            final ApnsClientHandler handler = new TokenAuthenticationApnsClientHandler(decoder, encoder, initialSettings, this.authority(), this.idlePingInterval(), this.signingKey(), this.tokenExpiration());
            this.frameListener(handler);
            return handler;
        }
    }

    protected TokenAuthenticationApnsClientHandler(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings, final String authority, final Duration idlePingInterval, final ApnsSigningKey signingKey, final Duration tokenExpiration) {
        super(decoder, encoder, initialSettings, authority, idlePingInterval);

        Objects.requireNonNull(signingKey, "Signing key must not be null for token-based client handlers.");
        this.signingKey = signingKey;
        this.tokenExpiration = tokenExpiration;
    }

    @Override
    protected Http2Headers getHeadersForPushNotification(final ApnsPushNotification pushNotification, final ChannelHandlerContext context, final int streamId) {
        final Http2Headers headers = super.getHeadersForPushNotification(pushNotification, context, streamId);

        if (this.authenticationToken == null) {
            try {
                log.debug("Generating new token for stream {}.", streamId);

                this.authenticationToken = new AuthenticationToken(signingKey, Instant.now());
                this.mostRecentStreamWithNewToken = streamId;

                this.expireTokenFuture = context.executor().schedule(() -> {
                    log.debug("Proactively expiring authentication token.");
                    TokenAuthenticationApnsClientHandler.this.authenticationToken = null;
                }, tokenExpiration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
                // This should never happen because we check the key/algorithm at signing key construction time.
                log.error("Failed to generate authentication token.", e);
                throw new RuntimeException(e);
            }
        }

        headers.add(APNS_AUTHORIZATION_HEADER, this.authenticationToken.getAuthorizationHeader());

        return headers;
    }

    @Override
    protected void handleErrorResponse(final ChannelHandlerContext context, final int streamId, final Http2Headers headers, final ApnsPushNotification pushNotification, final ErrorResponse errorResponse) {
        if (TokenAuthenticationApnsClientHandler.EXPIRED_AUTH_TOKEN_REASON.equals(errorResponse.getReason())) {
            log.warn("APNs server reports token has expired.");

            if (streamId >= this.mostRecentStreamWithNewToken) {
                this.authenticationToken = null;
            }

            // Once we've invalidated an expired token, it's reasonable to expect that re-sending the notification might
            // succeed.
            this.retryPushNotificationFromStream(context, streamId);
        } else {
            super.handleErrorResponse(context, streamId, headers, pushNotification, errorResponse);
        }
    }

    @Override
    protected void handlerRemoved0(final ChannelHandlerContext context) throws Exception {
        super.handlerRemoved0(context);

        // Cancel the token expiration future if it's still "live" to avoid a reference cycle that could keep handlers
        // for closed connections in memory longer than expected.
        if (expireTokenFuture != null) {
            expireTokenFuture.cancel(false);
        }
    }
}
