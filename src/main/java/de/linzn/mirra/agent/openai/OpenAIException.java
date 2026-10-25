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

    public OpenAIException(int statusCode, String message) {
        super(statusCode > 0 ? "OpenAI HTTP " + statusCode + ": " + message : message);
        this.statusCode = statusCode;
    }

    /**
     * @return HTTP status, 0 if the server was not reachable
     */
    public int getStatusCode() {
        return statusCode;
    }
}
