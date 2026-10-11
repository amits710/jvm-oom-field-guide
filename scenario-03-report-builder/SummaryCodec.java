/**
 * Shared summary codec for scenario 03.
 *
 * Lives once, in the scenario directory — both the broken and the fixed
 * version call the same method, so the experiment isolates *retention*, not
 * content. There is exactly one implementation; it cannot drift between
 * versions the way two duplicated copies could.
 *
 * The summary is a pure function of (record index, input byte, size): the
 * input record's data contributes to the output bytes, so the input side is
 * real work, not dead code. Both versions feed the identical input byte
 * ((byte)(i & 0xFF)), so both produce byte-for-byte identical summaries.
 */
final class SummaryCodec {

    private SummaryCodec() { } // utility class

    static byte[] makeSummary(int index, byte inputByte, int size) {
        byte[] s = new byte[size];
        for (int j = 0; j < size; j++) {
            s[j] = (byte) ((index * 31 + inputByte + j) & 0xFF);
        }
        return s;
    }
}
