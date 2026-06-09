/// A Java 25 compact source file (JEP 512): top-level members and an
/// instance {@code main} method, with no explicit class declaration. JDT
/// models these members as belonging to an implicitly declared class.
String greeting = "Hello";

void main() {
    System.out.println(message());
}

String message() {
    return greeting + ", world";
}
