package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds command-line arguments for Goose CLI invocations.
 * <p>
 * Supports different command modes:
 * </p>
 * <ul>
 *   <li>Single-shot execution via {@code goose session --text}</li>
 *   <li>Named sessions via {@code goose run -n}</li>
 *   <li>Streaming JSON output via {@code --output-format stream-json}</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class GooseCommandBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GooseCommandBuilder.class);

    private final String goosePath;

    /**
     * Creates a command builder with the specified Goose CLI path.
     *
     * @param goosePath path to the Goose CLI executable
     */
    public GooseCommandBuilder(String goosePath) {
        if (goosePath == null || goosePath.isEmpty()) {
            throw new IllegalArgumentException("Goose CLI path cannot be null or empty");
        }
        this.goosePath = goosePath;
    }

    /**
     * Build command for single-shot execution.
     * <p>
     * Command format: {@code goose session --text "prompt" --max-turns N}
     * </p>
     *
     * @param prompt the prompt to send
     * @param options execution options
     * @return the command as a list of arguments
     */
    public List<String> buildSingleShotCommand(String prompt, GooseOptions options) {
        List<String> command = new ArrayList<>();
        command.add(goosePath);
        command.add("session");
        command.add("--text");
        command.add(prompt);
        command.add("--max-turns");
        command.add(String.valueOf(options.maxTurns()));
        return command;
    }

    /**
     * Build command for named session execution.
     * <p>
     * For new sessions: {@code goose run -n "session-name" --provider X --model Y -t "prompt" --max-turns N}
     * For resuming: {@code goose run -n "session-name" -r --provider X --model Y -t "prompt" --max-turns N}
     * </p>
     *
     * @param sessionName the session name
     * @param prompt the prompt to send
     * @param resume whether to resume an existing session
     * @param options execution options
     * @return the command as a list of arguments
     */
    public List<String> buildSessionCommand(String sessionName, String prompt, boolean resume, GooseOptions options) {
        logger.info("Building session command: session='{}', resume={}, promptLength={}", 
            sessionName, resume, prompt.length());
        
        List<String> command = new ArrayList<>();
        command.add(goosePath);
        command.add("run");
        
        command.add("-n");
        command.add(sessionName);
        
        if (resume) {
            command.add("-r");
            logger.info("Session '{}': Adding resume flag (-r)", sessionName);
        } else {
            logger.info("Session '{}': New session (no resume flag)", sessionName);
        }
        
        addProviderAndModel(command, sessionName, options);
        
        // Extensions are configured in ~/.config/goose/config.yaml by the buildpack
        logger.info("Session '{}': Extensions loaded from config.yaml (not via CLI flags)", sessionName);
        
        command.add("-t");
        command.add(prompt);

        command.add("--max-turns");
        command.add(String.valueOf(options.maxTurns()));

        logCommand(sessionName, command, prompt);
        return command;
    }

    /**
     * Build command for streaming JSON output.
     * <p>
     * Uses {@code --output-format stream-json} for token-level streaming.
     * </p>
     *
     * @param sessionName the session name
     * @param prompt the prompt to send
     * @param resume whether to resume an existing session
     * @param options execution options
     * @return the command as a list of arguments
     */
    public List<String> buildStreamingJsonCommand(String sessionName, String prompt, boolean resume, GooseOptions options) {
        logger.info("Building streaming JSON session command: session='{}', resume={}, promptLength={}", 
            sessionName, resume, prompt.length());
        
        List<String> command = new ArrayList<>();
        command.add(goosePath);
        command.add("run");
        
        command.add("-n");
        command.add(sessionName);
        
        if (resume) {
            command.add("-r");
            logger.info("Session '{}': Adding resume flag (-r)", sessionName);
        } else {
            logger.info("Session '{}': New session (no resume flag)", sessionName);
        }
        
        addProviderAndModel(command, sessionName, options);
        
        command.add("-t");
        command.add(prompt);

        command.add("--output-format");
        command.add("stream-json");

        command.add("--max-turns");
        command.add(String.valueOf(options.maxTurns()));

        String promptPreview = prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt;
        logger.info("Session '{}': FULL command: {}", sessionName, String.join(" ", command));
        logger.info("Session '{}': Prompt preview: '{}'", sessionName, promptPreview);

        return command;
    }

    /**
     * Build command to get version.
     */
    public List<String> buildVersionCommand() {
        return List.of(goosePath, "--version");
    }

    /**
     * Adds provider and model arguments to the command.
     */
    private void addProviderAndModel(List<String> command, String sessionName, GooseOptions options) {
        String provider = options.provider();
        if (provider == null || provider.isEmpty()) {
            provider = System.getenv("GOOSE_PROVIDER");
        }
        if (provider != null && !provider.isEmpty()) {
            command.add("--provider");
            command.add(provider);
            logger.debug("Session '{}': Using provider '{}'", sessionName, provider);
        }
        
        String model = options.model();
        if (model == null || model.isEmpty()) {
            model = System.getenv("GOOSE_MODEL");
        }
        if (model != null && !model.isEmpty()) {
            command.add("--model");
            command.add(model);
            logger.debug("Session '{}': Using model '{}'", sessionName, model);
        }
    }

    /**
     * Logs the command being executed.
     */
    private void logCommand(String sessionName, List<String> command, String prompt) {
        String promptPreview = prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt;
        // Log command without the last 4 elements (-t, prompt, --max-turns, N)
        int endIndex = Math.max(0, command.size() - 4);
        logger.info("Session '{}': Full command: {} (prompt: '{}')", 
            sessionName, 
            String.join(" ", command.subList(0, endIndex)),
            promptPreview);
    }
}
