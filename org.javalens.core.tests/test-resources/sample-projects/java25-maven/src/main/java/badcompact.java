/// A JEP 512 compact source file in a deliberately lowercase-named file, so the
/// implicitly declared class is named "badcompact" — which would be a PascalCase
/// class-naming violation IF the implicit class name were (wrongly) checked. It
/// must not be: implicit classes have no source-level name to validate. The
/// PascalCase method name below is a genuine camelCase violation that must still
/// be reported, proving member checks descend into the implicit class.
void main() {
    HelperMethod();
}

void HelperMethod() {
}
