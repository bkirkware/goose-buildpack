package org.tanzu.goose.cf;

/**
 * Filters Goose CLI output to remove banner/startup lines.
 * <p>
 * The Goose CLI outputs a startup banner that includes:
 * </p>
 * <ul>
 *   <li>{@code starting session | provider: openai model: gpt-4o}</li>
 *   <li>{@code session id: 20260108_1}</li>
 *   <li>{@code working directory: /home/vcap/app}</li>
 * </ul>
 * <p>
 * Or for resumed sessions:
 * </p>
 * <ul>
 *   <li>{@code resuming session | provider: openai model: gpt-4o}</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public final class GooseOutputFilter {

    private GooseOutputFilter() {
        // Utility class
    }

    /**
     * Check if a line is part of the Goose CLI startup banner.
     *
     * @param line the line to check
     * @return true if the line is a banner line
     */
    public static boolean isBannerLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("starting session |") ||
               trimmed.startsWith("resuming session |") ||
               trimmed.startsWith("session id:") ||
               trimmed.startsWith("working directory:");
    }

    /**
     * Filter out Goose CLI banner/startup lines from output.
     * <p>
     * Removes all banner lines from the start of the output until
     * the first non-banner, non-empty line is encountered.
     * </p>
     *
     * @param output the raw output from Goose CLI
     * @return the output with banner lines removed
     */
    public static String filterBanner(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        
        StringBuilder filtered = new StringBuilder();
        String[] lines = output.split("\n");
        boolean inBanner = true;
        
        for (String line : lines) {
            if (inBanner) {
                if (isBannerLine(line) || line.trim().isEmpty()) {
                    continue;
                }
                inBanner = false;
            }
            
            filtered.append(line).append("\n");
        }
        
        String result = filtered.toString();
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        
        return result;
    }
}
