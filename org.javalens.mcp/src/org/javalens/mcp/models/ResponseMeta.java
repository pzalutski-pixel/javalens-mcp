package org.javalens.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for tool responses.
 * Includes pagination info, truncation status, and suggestions for AI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseMeta {

    private Integer totalCount;
    private Integer returnedCount;
    private Boolean truncated;
    private List<String> suggestedNextTools;
    private String verbosity;

    private ResponseMeta() {
        // Use builder
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public Integer getReturnedCount() {
        return returnedCount;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public List<String> getSuggestedNextTools() {
        return suggestedNextTools;
    }

    public String getVerbosity() {
        return verbosity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ResponseMeta meta = new ResponseMeta();

        public Builder totalCount(Integer totalCount) {
            meta.totalCount = totalCount;
            return this;
        }

        public Builder returnedCount(Integer returnedCount) {
            meta.returnedCount = returnedCount;
            return this;
        }

        public Builder truncated(Boolean truncated) {
            meta.truncated = truncated;
            return this;
        }

        public Builder suggestedNextTools(List<String> suggestedNextTools) {
            meta.suggestedNextTools = suggestedNextTools;
            return this;
        }

        public Builder addSuggestedNextTool(String tool) {
            if (meta.suggestedNextTools == null) {
                meta.suggestedNextTools = new ArrayList<>();
            }
            meta.suggestedNextTools.add(tool);
            return this;
        }

        public Builder verbosity(String verbosity) {
            meta.verbosity = verbosity;
            return this;
        }

        public ResponseMeta build() {
            return meta;
        }
    }
}
