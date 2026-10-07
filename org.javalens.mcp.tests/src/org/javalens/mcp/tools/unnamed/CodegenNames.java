package org.javalens.mcp.tools.unnamed;

/**
 * The codegen-names fixture.
 *
 * <p>Package com.example.gen.model declares {@code Stamp}, with the public static member class
 * {@code Ink}, and the interface {@code Seal}. Package com.example.gen declares:
 * <ul>
 *   <li>{@code Desk} (imports Stamp), with the public static member class {@code Voucher}
 *       (line 6), which declares the field {@code Stamp stamp} (line 8) and the method
 *       {@code public int value(int base)} (line 10);</li>
 *   <li>{@code Route}, which imports the member type {@code com.example.gen.Desk.Voucher} and
 *       names it four times: the field {@code Voucher voucher} and the method
 *       {@code public Desk.Voucher renew(Voucher old)}, its return type written qualified;</li>
 *   <li>{@code Clerk} (line 10, name from column 13), which imports Seal, Stamp, the member type
 *       Stamp.Ink, IOException, ArrayList, List and the member type Map.Entry, and declares
 *       {@code public List<Stamp> stamps(Entry<String, Ink> key) throws IOException}, whose body
 *       alone uses ArrayList, {@code public Desk.Voucher issue(String note, Route route)} and
 *       {@code public <S extends Seal> S sealed(S item)};</li>
 *   <li>{@code Tally<V extends Stamp>} (line 4, name from column 13, its type parameter list
 *       closing at column 35), which imports Stamp and declares {@code public V top()};</li>
 *   <li>{@code Notes}, whose {@code write()} calls the void method {@code log("start")}
 *       (line 11, columns 8 to 20) and assigns {@code null} (line 12, columns 15 to 19) to the
 *       field {@code last};</li>
 *   <li>{@code Shapes} (line 2), which declares the public member record
 *       {@code Size(int width, int height)} (line 4) with the method {@code public int area()}
 *       (line 6), and the method {@code public int total(int count)} (line 11), whose body
 *       declares the local record {@code Run(int start, int end)} (line 12);</li>
 *   <li>{@code Ticket}, whose field {@code Voucher voucher} (line 4) names Voucher unimported:
 *       the one compile error of the fixture.</li>
 * </ul>
 * The default package declares {@code Voucher}. No JDK type is named Voucher. Positions are
 * zero-based.
 */
final class CodegenNames {

    static final String FIXTURE = "codegen-names";
    static final String GEN = "src/main/java/com/example/gen/";

    private CodegenNames() {
    }
}
