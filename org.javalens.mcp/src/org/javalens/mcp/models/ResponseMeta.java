package org.javalens.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for tool responses.
 * Includes pagination info, truncation status, suggestions for AI, the files the search index
 * is missing while it is verified incomplete, and the libraries whose index could not be built.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseMeta {

    private Integer totalCount;
    private Integer returnedCount;
    private Boolean truncated;
    private List<String> suggestedNextTools;
    private String verbosity;
    private List<String> indexIncomplete;
    private List<String> libraryIndexIncomplete;

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

    /**
     * The libraries on the classpath whose search index could not be built at load, as the
     * LIBRARY_INDEX_INCOMPLETE warning names them (one under the project root relative to it,
     * any other by its absolute path, with '/' separators), sorted, or null while every library
     * is searchable. Search-based parts of the response do not include those libraries' types.
     */
    public List<String> getLibraryIndexIncomplete() {
        return libraryIndexIncomplete;
    }

    /** A copy of this meta whose {@code indexIncomplete} names {@code files}. */
    ResponseMeta withIndexIncomplete(List<String> files) {
        ResponseMeta copy = copy();
        copy.indexIncomplete = List.copyOf(files);
        return copy;
    }

    /** A copy of this meta whose {@code libraryIndexIncomplete} names {@code libraries}. */
    ResponseMeta withLibraryIndexIncomplete(List<String> libraries) {
        ResponseMeta copy = copy();
        copy.libraryIndexIncomplete = List.copyOf(libraries);
        return copy;
    }

    private ResponseMeta copy() {
        ResponseMeta copy = new ResponseMeta();
        copy.totalCount = totalCount;
        copy.returnedCount = returnedCount;
        copy.truncated = truncated;
        copy.suggestedNextTools = suggestedNextTools;
        copy.verbosity = verbosity;
        copy.indexIncomplete = indexIncomplete;
        copy.libraryIndexIncomplete = libraryIndexIncomplete;
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
