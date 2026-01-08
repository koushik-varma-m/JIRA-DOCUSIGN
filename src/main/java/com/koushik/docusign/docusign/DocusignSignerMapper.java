package com.koushik.docusign.docusign;

import com.atlassian.jira.user.ApplicationUser;
import com.koushik.docusign.docusign.DocusignService.DocusignSigner;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps resolved signers into DocuSign signers with ordered routing.
 */
public class DocusignSignerMapper {

    /**
     * Convert ordered Jira users to DocuSign signers.
     * routingOrder and recipientId are assigned incrementally starting at 1.
     */
    public List<DocusignSigner> mapUsersToSigners(List<ApplicationUser> users) {
        if (users == null) {
            return new ArrayList<>();
        }
        List<ResolvedSigner> resolved = new ArrayList<>();
        for (ApplicationUser user : users) {
            if (user == null) {
                throw new IllegalArgumentException("User is null");
            }
            String email = safe(user.getEmailAddress());
            if (email.isEmpty()) {
                throw new IllegalArgumentException("User " + user.getDisplayName() + " has no email address.");
            }
            String name = safe(user.getDisplayName());
            if (name.isEmpty()) {
                name = safe(user.getName());
            }
            resolved.add(ResolvedSigner.jira(name, email));
        }
        return mapResolvedSigners(resolved);
    }

    /**
     * Convert ordered resolved signers (JIRA_USER or EXTERNAL) to DocuSign signers.
     * routingOrder and recipientId are assigned incrementally starting at 1.
     */
    public List<DocusignSigner> mapResolvedSigners(List<ResolvedSigner> signers) {
        List<DocusignSigner> result = new ArrayList<>();
        if (signers == null) {
            return result;
        }
        for (int i = 0; i < signers.size(); i++) {
            ResolvedSigner s = signers.get(i);
            if (s == null) {
                throw new IllegalArgumentException("Signer at index " + i + " is null");
            }
            String email = safe(s.getEmail());
            if (email.isEmpty()) {
                throw new IllegalArgumentException("Signer at index " + i + " has no email address.");
            }
            String name = safe(s.getName());
            if (name.isEmpty()) {
                name = email;
            }
            String order = String.valueOf(i + 1);
            String recipientId = order;
            result.add(new DocusignSigner(name, email, recipientId, order));
        }
        return result;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Simple resolved signer holder.
     */
    public static class ResolvedSigner {
        public enum Type { JIRA_USER, EXTERNAL }

        private final Type type;
        private final String name;
        private final String email;

        private ResolvedSigner(Type type, String name, String email) {
            this.type = type;
            this.name = name;
            this.email = email;
        }

        public static ResolvedSigner jira(String name, String email) {
            return new ResolvedSigner(Type.JIRA_USER, name, email);
        }

        public static ResolvedSigner external(String email) {
            return new ResolvedSigner(Type.EXTERNAL, email, email);
        }

        public Type getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }
    }
}
