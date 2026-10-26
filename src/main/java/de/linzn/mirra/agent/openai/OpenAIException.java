/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.agent.openai;

/**
 * Error of the OpenAI API.
 */
public class OpenAIException extends Exception {

    private final int statusCode;
    /* value of the Retry-After header, -1 if not present */
    int retryAfterSeconds = -1;

    /* error code of the API, e.g. insufficient_quota, may be null */
    private final String errorCode;

    public OpenAIException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public OpenAIException(int statusCode, String message, String errorCode) {
        super(statusCode > 0 ? "OpenAI HTTP " + statusCode + ": " + message : message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /**
     * @return true if the account has no credits left or reached its hard limit
     */
    public boolean isQuotaExceeded() {
        String message = String.valueOf(this.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return "insufficient_quota".equals(this.errorCode) || "billing_hard_limit_reached".equals(this.errorCode)
                || message.contains("exceeded your current quota") || message.contains("billing hard limit");
    }

    /**
     * @return true if the API key is missing, invalid or revoked
     */
    public boolean isAuthError() {
        return this.statusCode == 401;
    }

    /**
     * @return HTTP status, 0 if the server was not reachable
     */
    public int getStatusCode() {
        return statusCode;
    }
}
