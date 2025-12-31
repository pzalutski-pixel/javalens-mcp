package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Get parsed Javadoc documentation for a symbol.
 */
public class GetJavadocTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetJavadocTool.class);

    private static final Pattern PARAM_PATTERN = Pattern.compile("@param\\s+(\\w+)\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern RETURN_PATTERN = Pattern.compile("@return\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern THROWS_PATTERN = Pattern.compile("@throws\\s+(\\S+)\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern SEE_PATTERN = Pattern.compile("@see\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern SINCE_PATTERN = Pattern.compile("@since\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern DEPRECATED_PATTERN = Pattern.compile("@deprecated\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("@author\\s+(.+?)(?=@|$)", Pattern.DOTALL);
    private static final Pattern VERSION_PATTERN = Pattern.compile("@version\\s+(.+?)(?=@|$)", Pattern.DOTALL);

    public GetJavadocTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_javadoc";
    }

    @Override
    public String getDescription() {
        return """
            Get parsed Javadoc documentation for a symbol.

            USAGE: Position on any documented symbol
            OUTPUT: Parsed Javadoc with summary, @param, @return, @throws, etc.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            if (!(element instanceof IMember member)) {
                return ToolResponse.invalidParameter("position", "Symbol at position does not have Javadoc");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("kind", getElementKind(element));

            // Get Javadoc source
            String javadoc = getJavadocSource(member);
            if (javadoc == null || javadoc.isBlank()) {
                data.put("hasDocumentation", false);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .suggestedNextTools(List.of("get_hover_info for basic info"))
                    .build());
            }

            data.put("hasDocumentation", true);

            // Parse the Javadoc
            parseJavadoc(javadoc, data, element);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to find usages",
                    "get_type_members for related members"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting javadoc: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private String getJavadocSource(IMember member) {
        try {
            ISourceRange javadocRange = member.getJavadocRange();
            if (javadocRange == null) {
                return null;
            }

            ICompilationUnit cu = member.getCompilationUnit();
            if (cu == null) {
                return null;
            }

            String source = cu.getSource();
            if (source == null) {
                return null;
            }

            int start = javadocRange.getOffset();
            int end = start + javadocRange.getLength();

            if (start >= 0 && end <= source.length()) {
                return source.substring(start, end);
            }

            return null;
        } catch (JavaModelException e) {
            log.debug("Error getting javadoc source: {}", e.getMessage());
            return null;
        }
    }

    private void parseJavadoc(String javadoc, Map<String, Object> data, IJavaElement element) {
        // Clean up the javadoc
        String cleaned = javadoc
            .replaceAll("/\\*\\*", "")
            .replaceAll("\\*/", "")
            .replaceAll("(?m)^\\s*\\*\\s?", "")
            .trim();

        // Extract summary (everything before first @tag)
        int firstTag = cleaned.indexOf("@");
        String summary;
        String tagSection;

        if (firstTag > 0) {
            summary = cleaned.substring(0, firstTag).trim();
            tagSection = cleaned.substring(firstTag);
        } else if (firstTag == 0) {
            summary = "";
            tagSection = cleaned;
        } else {
            summary = cleaned;
            tagSection = "";
        }

        if (!summary.isEmpty()) {
            data.put("summary", summary);
        }

        // Parse @param tags
        List<Map<String, String>> params = new ArrayList<>();
        Matcher paramMatcher = PARAM_PATTERN.matcher(tagSection);
        while (paramMatcher.find()) {
            Map<String, String> param = new LinkedHashMap<>();
            param.put("name", paramMatcher.group(1).trim());
            param.put("description", paramMatcher.group(2).trim());
            params.add(param);
        }
        if (!params.isEmpty()) {
            data.put("params", params);
        }

        // Parse @return
        Matcher returnMatcher = RETURN_PATTERN.matcher(tagSection);
        if (returnMatcher.find()) {
            data.put("returns", returnMatcher.group(1).trim());
        }

        // Parse @throws
        List<Map<String, String>> throwsList = new ArrayList<>();
        Matcher throwsMatcher = THROWS_PATTERN.matcher(tagSection);
        while (throwsMatcher.find()) {
            Map<String, String> throwsEntry = new LinkedHashMap<>();
            throwsEntry.put("type", throwsMatcher.group(1).trim());
            throwsEntry.put("description", throwsMatcher.group(2).trim());
            throwsList.add(throwsEntry);
        }
        if (!throwsList.isEmpty()) {
            data.put("throws", throwsList);
        }

        // Parse @see
        List<String> seeList = new ArrayList<>();
        Matcher seeMatcher = SEE_PATTERN.matcher(tagSection);
        while (seeMatcher.find()) {
            seeList.add(seeMatcher.group(1).trim());
        }
        if (!seeList.isEmpty()) {
            data.put("see", seeList);
        }

        // Parse @since
        Matcher sinceMatcher = SINCE_PATTERN.matcher(tagSection);
        if (sinceMatcher.find()) {
            data.put("since", sinceMatcher.group(1).trim());
        }

        // Parse @deprecated
        Matcher deprecatedMatcher = DEPRECATED_PATTERN.matcher(tagSection);
        if (deprecatedMatcher.find()) {
            data.put("deprecated", deprecatedMatcher.group(1).trim());
        }

        // Parse @author
        List<String> authors = new ArrayList<>();
        Matcher authorMatcher = AUTHOR_PATTERN.matcher(tagSection);
        while (authorMatcher.find()) {
            authors.add(authorMatcher.group(1).trim());
        }
        if (!authors.isEmpty()) {
            data.put("authors", authors);
        }

        // Parse @version
        Matcher versionMatcher = VERSION_PATTERN.matcher(tagSection);
        if (versionMatcher.find()) {
            data.put("version", versionMatcher.group(1).trim());
        }
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            default -> "Unknown";
        };
    }
}
