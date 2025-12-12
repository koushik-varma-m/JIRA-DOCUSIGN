package com.koushik.docusign;

import com.atlassian.jira.issue.Issue;
import com.atlassian.plugin.web.ContextProvider;

import java.util.HashMap;
import java.util.Map;

public class DocusignContextProvider implements ContextProvider {

    @Override
    public void init(Map<String, String> params) {
    }

    @Override
    public Map<String, Object> getContextMap(Map<String, Object> context) {
        Map<String, Object> map = new HashMap<>();
        
        Issue issue = (Issue) context.get("issue");
        map.put("issueKey", issue.getKey());
        
        return map;
    }
}
