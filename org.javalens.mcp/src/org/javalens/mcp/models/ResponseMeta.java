package org.javalens.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for tool responses.
 * Includes pagination info, truncation status, suggestions for AI, and the files the
 * search index is missing while it is verified incomplete.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseMeta {

    private Integer totalCount;
    private Integer returnedCount;
    private Boolean truncated;
    private List<String> suggestedNextTools;
    private String verbosity;
    private List<String> indexIncomplete;

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

    /**
     * The source files whose declarations the search index is missing, relative to the project
     * root and sorted, or null while the index is complete. Search-based parts of the response
     * do not include those files' types or references.
     */
    public List<String> getIndexIncomplete() {
        return indexIncomplete;
    }

    /** A copy of this meta whose {@code indexIncomplete} names {@code files}. */
    ResponseMeta withIndexIncomplete(List<String> files) {
        ResponseMeta copy = new ResponseMeta();
        copy.totalCount = totalCount;
        copy.returnedCount = returnedCount;
        copy.truncated = truncated;
        copy.suggestedNextTools = suggestedNextTools;
        copy.verbosity = verbosity;
        copy.indexIncomplete = List.copyOf(files);
        return copy;
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
