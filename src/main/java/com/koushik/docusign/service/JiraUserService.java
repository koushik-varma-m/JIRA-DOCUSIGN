package com.koushik.docusign.service;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleActors;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service to fetch assignable Jira users for a given issue's project.
 */
public class JiraUserService {

    private final ProjectRoleManager projectRoleManager;
    private final UserManager userManager;

    public JiraUserService() {
        this.projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager.class);
        this.userManager = ComponentAccessor.getUserManager();
    }

    /**
     * Returns assignable users for the project associated with the given issue.
     * Users are deduplicated across roles.
     */
    public List<JiraUser> getAssignableUsersForIssue(Issue issue) {
        if (issue == null) {
            throw new IllegalArgumentException("issue is required");
        }
        Project project = issue.getProjectObject();
        if (project == null) {
            return new ArrayList<>();
        }

        Set<String> seenUserKeys = new HashSet<>();
        List<JiraUser> result = new ArrayList<>();

        for (ProjectRole role : projectRoleManager.getProjectRoles()) {
            ProjectRoleActors actors = projectRoleManager.getProjectRoleActors(role, project);
            for (ApplicationUser user : actors.getApplicationUsers()) {
                if (user == null) {
                    continue;
                }
                String userKey = user.getKey();
                if (userKey == null || seenUserKeys.contains(userKey)) {
                    continue;
                }

                // Only include active/known users
                ApplicationUser resolved = userManager.getUserByKey(userKey);
                if (resolved == null) {
                    continue;
                }

                seenUserKeys.add(userKey);
                String email = resolved.getEmailAddress();
                result.add(new JiraUser(userKey, resolved.getDisplayName(), email != null ? email : ""));
            }
        }

        return result;
    }

    /**
     * Simple DTO for UI consumption.
     */
    public static class JiraUser {
        private final String userKey;
        private final String displayName;
        private final String emailAddress;

        public JiraUser(String userKey, String displayName, String emailAddress) {
            this.userKey = userKey;
            this.displayName = displayName;
            this.emailAddress = emailAddress;
        }

        public String getUserKey() {
            return userKey;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEmailAddress() {
            return emailAddress;
        }
    }
}
