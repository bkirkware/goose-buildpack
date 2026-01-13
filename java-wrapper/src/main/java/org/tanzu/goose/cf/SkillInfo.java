package org.tanzu.goose.cf;

/**
 * Information about a configured Goose skill.
 * <p>
 * Skills are reusable instruction sets that teach Goose how to perform
 * specific tasks. They can be defined inline, loaded from files, or
 * cloned from Git repositories.
 * </p>
 *
 * @param name the skill name (unique identifier)
 * @param description optional description of the skill
 * @param source the source type: "inline", "file", or "git"
 * @param path the path to the skill (for file-based or git-based skills)
 * @param repository the git repository URL (for git-based skills)
 * @param branch the git branch (for git-based skills)
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public record SkillInfo(
    String name,
    String description,
    String source,
    String path,
    String repository,
    String branch
) {
    /**
     * Creates an inline skill with the given name and description.
     */
    public static SkillInfo inline(String name, String description) {
        return new SkillInfo(name, description, "inline", null, null, null);
    }

    /**
     * Creates a file-based skill.
     */
    public static SkillInfo fromFile(String name, String path) {
        return new SkillInfo(name, null, "file", path, null, null);
    }

    /**
     * Creates a git-based skill.
     */
    public static SkillInfo fromGit(String name, String repository, String branch, String path) {
        return new SkillInfo(name, null, "git", path, repository, branch);
    }
}
