/**
 * Fixture for get_project_structure default-package coverage.
 * No package declaration — JDT reports this in the default package, which
 * the tool's createPackageInfo renders as "(default package)".
 */
public class NoPackage {

    public String hello() {
        return "no package";
    }
}
