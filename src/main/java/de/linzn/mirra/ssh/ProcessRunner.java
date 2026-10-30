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

package de.linzn.mirra.ssh;

import de.linzn.stem.utils.ProcessOutput;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a process with a timeout and collects the merged output (shell commands, SSH).
 */
public final class ProcessRunner {

    public static final int MAX_OUTPUT_LINES = 300;

    private ProcessRunner() {
    }

    /**
     * @param stdin text written to the standard input, may be null
     * @return finished, exit_code (or error on timeout), output (last {@link #MAX_OUTPUT_LINES} lines)
     */
    public static JSONObject run(List<String> command, String stdin, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            ProcessOutput output = ProcessOutput.drain(process, "Mirra-Shell");
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ignored) {
                /* the process ended before reading its input */
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            output.awaitCompletion(5, TimeUnit.SECONDS);
            List<String> lines = output.getLines();
            boolean truncated = lines.size() > MAX_OUTPUT_LINES;
            if (truncated) {
                lines = lines.subList(lines.size() - MAX_OUTPUT_LINES, lines.size());
            }
            JSONObject result = new JSONObject().put("finished", finished).put("output", new JSONArray(lines));
            if (finished) {
                result.put("exit_code", process.exitValue());
            } else {
                result.put("error", "Timeout after " + timeoutSeconds + " s, process killed");
            }
            if (truncated) {
                result.put("note", "Only the last " + MAX_OUTPUT_LINES + " lines");
            }
            return result;
        } finally {
            ProcessOutput.closeStreams(process);
        }
    }

    /**
     * @return true if the result of {@link #run} finished with exit code 0
     */
    public static boolean ok(JSONObject result) {
        return result.optBoolean("finished") && result.optInt("exit_code", -1) == 0;
    }

    /**
     * @return the output lines of a result of {@link #run} as one text
     */
    public static String text(JSONObject result) {
        JSONArray lines = result.optJSONArray("output");
        if (lines == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length(); i++) {
            builder.append(lines.optString(i)).append('\n');
        }
        return builder.toString().trim();
    }
}
