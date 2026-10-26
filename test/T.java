/** Minimal assertions for the test mains run by test.sh. */
final class T {
    private static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    static void eq(String name, Object expected, Object actual) {
        check(name, java.util.Objects.equals(expected, actual), "expected <" + expected + "> got <" + actual + ">");
    }

    /** Call last in each test main. */
    static void done() {
        if (failures > 0) System.exit(1);
    }
}
